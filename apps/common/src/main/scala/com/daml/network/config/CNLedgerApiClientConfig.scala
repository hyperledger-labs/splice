package com.daml.network.config

import akka.actor.ActorSystem
import com.daml.network.auth.AuthTokenSource
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** @param clientConfig Connection parameters
  * @param authConfig Auth tokens used by the app
  */
case class CNLedgerApiClientConfig(
    clientConfig: ClientConfig,
    authConfig: AuthTokenSourceConfig,
) {
  // Note: Some places, e.g., Cantons RemoteParticipantConfig expects a static token,
  // CNLedgerApiClientConfig contains information for how to acquire tokens.
  // We need to perform some blocking IO to generate the token here.
  def getToken()(implicit actorSystem: ActorSystem): Option[String] = {
    implicit val executionContext = actorSystem.dispatcher
    implicit val traceContext = TraceContext.empty
    val loggerFactory = NamedLoggerFactory.root
    val authTokenSource = AuthTokenSource.fromConfig(
      authConfig,
      loggerFactory,
    )
    Await.result(authTokenSource.getToken, 30.seconds)
  }
}
