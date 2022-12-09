package com.daml.network.config

import com.daml.network.auth.AuthTokenSource
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** @param clientConfig Connection parameters
  * @param authConfig Auth tokens used by the app
  */
case class CoinLedgerApiClientConfig(
    clientConfig: ClientConfig,
    authConfig: AuthTokenSourceConfig,
) {
  // Note: Some places, e.g., Cantons RemoteParticipantConfig expects a static token,
  // CoinLedgerApiClientConfig contains information for how to acquire tokens.
  // We need to perform some blocking IO to generate the token here.
  def getToken(): Option[String] = {
    val loggerFactory = NamedLoggerFactory.root
    val authTokenSource = AuthTokenSource.fromConfig(
      authConfig,
      loggerFactory,
      ProcessingTimeout(),
    )
    Await.result(authTokenSource.getToken, 30.seconds)
  }
}
