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
  private sealed trait ResultState extends State
  private case object NoToken extends ResultState
  private case class HaveToken(token: AuthToken) extends ResultState
  private case class Refreshing(pending: Future[ResultState]) extends State

  private val state = new AtomicReference[State](NoToken)

  /** Request a token.
    * If a token is immediately available the returned future will be immediately completed.
    * If there is no token it will cause a token refresh to start and be completed once obtained.
    * If there is a refresh already in progress it will be completed with this refresh.
    * If a scheduled refresh occurs while a refresh is in progress, the result of the scheduled refresh will be returned.
    */
  def getToken: Future[Option[AuthToken]] = blocking {
    state.get() match {
      case HaveToken(token) => Future.successful(Some(token))
      case NoToken => tokenO(refreshState())
      case Refreshing(pending) => tokenO(pending)
    }
  }

  private def tokenO(pending: Future[ResultState]): Future[Option[AuthToken]] = pending.map {
    case HaveToken(token) => Some(token)
    case NoToken => None
  }

  private def refreshState(): Future[ResultState] = {
    val promise = Promise[ResultState]()
    val resultF = promise.future
    val prevState = state.compareAndExchange(NoToken, Refreshing(resultF))
    if (prevState == NoToken) {
      refreshToken(promise)
    } else {
      prevState match {
        case r: ResultState => promise.success(r)
        case _ => promise.failure(new Exception("prevState == NoToken"))
      }
    }
    resultF
  }

  private def refreshToken(
      promise: Promise[ResultState]
  ): Unit = {
    import com.digitalasset.canton.tracing.TraceContext.Implicits.Empty.*

    logger.debug("Refreshing authentication token")
    obtainToken().onComplete {
      case Failure(exception) =>
        logger.warn("Token refresh failed", exception)
        state.set(NoToken)
        promise.failure(exception)
      case Success(None) =>
        state.set(NoToken)
        promise.success(NoToken)
      case Success(Some(authToken)) =>
        val nextState = HaveToken(authToken)
        state.set(nextState)
        logger.debug("Token refresh complete")
        scheduleRefreshBefore(authToken.expiresAt)
        promise.success(nextState)
    }
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
    val promise = Promise[ResultState]()
    val future = promise.future
    state.get() match {
      // if a pending refresh is already occurring, overwrite the result with the scheduled refresh token
      case Refreshing(pending) => pending.foreach(_ => future)
      // getToken calls, that occur after the background refresh starts, will get the result of the background refresh
      case _ => state.set(Refreshing(future))
    }
    refreshToken(promise)
  }
}
