// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.auth

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import com.digitalasset.canton.tracing.TraceContext.Implicits.Empty.*

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
  private case class Refreshing(
      pending: Future[ResultState],
      startedAt: CantonTimestamp = CantonTimestamp.now(),
  ) extends State

  private val state = new AtomicReference[State](NoToken)

  /** Request a token.
    * If a token is immediately available the returned future will be immediately completed.
    * If there is no token it will cause a token refresh to start and be completed once obtained.
    * If there is a refresh already in progress it will be completed with this refresh.
    * If a scheduled refresh occurs while a refresh is in progress, eventually the last completing refresh will be returned.
    */
  def getToken: Future[Option[AuthToken]] = {
    state.get() match {
      case HaveToken(token) => Future.successful(Some(token))
      case NoToken => tokenO(refreshState())
      case Refreshing(pending, _) => tokenO(pending)
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
        case Refreshing(pending, startedAt) =>
          logger.debug(
            s"Refresh is already in progress, started at: $startedAt"
          )
          promise.completeWith(pending)
        case r: ResultState => promise.success(r)
      }
    }
    resultF
  }

  private def refreshToken(
      promise: Promise[ResultState]
  ): Unit = {
    val currentTokenMsg = state.get() match {
      case HaveToken(token) => s", which expires at: ${token.expiresAt}"
      case NoToken => ", currently holding no token"
      case Refreshing(_, startedAt) =>
        s", while a refresh is in progress that started at: $startedAt"
    }
    logger.debug(s"Refreshing authentication token${currentTokenMsg}")

    obtainToken().onComplete {
      case Failure(exception) =>
        logger.warn("Token refresh failed", exception)
        state.set(NoToken)
        promise.failure(exception)
      case Success(None) =>
        logger.debug("obtain token returned no token")
        state.set(NoToken)
        promise.success(NoToken)
      case Success(Some(authToken)) =>
        val nextState = HaveToken(authToken)
        state.set(nextState)
        logger.debug(s"Token refresh complete, new token expires at: ${authToken.expiresAt}")
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
    // overwrite any existing state with this background refresh
    state.set(Refreshing(future))
    refreshToken(promise)
  }
}
