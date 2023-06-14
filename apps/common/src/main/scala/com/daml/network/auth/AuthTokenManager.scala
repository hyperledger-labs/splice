package com.daml.network.auth

import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.{Failure, Success}

/** Attempts to hold a valid authentication token.
  * The first token will not be fetched until `getToken` is called for the first time.
  * Subsequent calls to `getToken` before the token is obtained will be resolved for the first token.
  * `getToken` always returns a `Future[Option[AuthToken]]` but if a token is already available will be completed immediately with that token.
  */
class AuthTokenManager(
    obtainToken: () => Future[Option[AuthToken]],
    isClosed: => Boolean,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
    refreshAuthTokenBeforeExpiry: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(2),
)(implicit executionContext: ExecutionContext)
    extends NamedLogging {

  sealed trait State
  case object NoToken extends State
  case class Refreshing(pending: Future[Option[AuthToken]]) extends State
  case class HaveToken(token: AuthToken) extends State

  private val state = new AtomicReference[State](NoToken)

  /** Request a token.
    * If a token is immediately available the returned future will be immediately completed.
    * If there is no token it will cause a token refresh to start and be completed once obtained.
    * If there is a refresh already in progress it will be completed with this refresh.
    */
  def getToken: Future[Option[AuthToken]] = blocking {
    // updates must be synchronized, as we are triggering refreshes from here
    // and the AtomicReference.updateAndGet requires the update to be side-effect free
    synchronized {
      state.get() match {
        // we are already refreshing, so pass future result
        case Refreshing(pending) => pending
        // we have a token, so share it
        case HaveToken(token) => Future.successful(Some(token))
        // there is no token yet, so start refreshing and return pending result
        case NoToken =>
          createRefreshTokenFuture()
      }
    }
  }

  private def createRefreshTokenFuture(): Future[Option[AuthToken]] = {
    import com.digitalasset.canton.tracing.TraceContext.Implicits.Empty.*

    val syncP = Promise[Unit]()
    val refresh = syncP.future.flatMap(_ => obtainToken())

    logger.debug("Refreshing authentication token")

    def completeRefresh(result: State): Unit = {
      state.updateAndGet {
        case Refreshing(_) => result
        case other => other
      }.discard
    }

    // asynchronously update the state once completed, one way or another
    val refreshTransformed = refresh.andThen {
      case Failure(exception) =>
        logger.warn("Token refresh failed", exception)
        completeRefresh(NoToken)
      case Success(None) =>
        completeRefresh(NoToken)
      case Success(Some(authToken)) =>
        logger.debug("Token refresh complete")
        scheduleRefreshBefore(authToken.expiresAt)
        completeRefresh(HaveToken(authToken))
    }

    val res = Refreshing(refresh)
    state.set(res)
    // only kick off computation once the state is set
    syncP.success(())
    refreshTransformed
  }

  private def scheduleRefreshBefore(expiresAt: CantonTimestamp): Unit = {
    if (!isClosed) {
      clock
        .scheduleAt(
          backgroundRefreshToken,
          expiresAt.minus(refreshAuthTokenBeforeExpiry.asJava),
        )
        .discard
    }
  }

  private def backgroundRefreshToken(_now: CantonTimestamp): Unit = if (!isClosed) {
    _now.discard
    blocking(synchronized(createRefreshTokenFuture().discard))
  }

}
