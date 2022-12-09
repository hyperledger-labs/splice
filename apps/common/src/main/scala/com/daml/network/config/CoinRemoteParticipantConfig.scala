package com.daml.network.config

import com.daml.network.auth.AuthTokenSource
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.config.{BaseParticipantConfig, RemoteParticipantConfig}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Configuration to connect the console to a participant running remotely.
  *
  * @param adminApi the configuration to connect the console to the remote admin api
  * @param ledgerApi the configuration to connect the console to the remote ledger api
  */
case class CoinRemoteParticipantConfig(
    adminApi: ClientConfig,
    ledgerApi: CoinLedgerApiClientConfig,
) extends BaseParticipantConfig {
  override def clientAdminApi: ClientConfig = adminApi
  override def clientLedgerApi: ClientConfig = ledgerApi.clientConfig

  def remoteParticipantConfig: RemoteParticipantConfig = {
    // Note: Cantons RemoteParticipantConfig expects a static token,
    // CoinLedgerApiClientConfig contains information for how to acquire tokens.
    // We need to perform some blocking IO to generate a RemoteParticipantConfig.
    val loggerFactory = NamedLoggerFactory.root
    val authTokenSource = AuthTokenSource.fromConfig(
      ledgerApi.authConfig,
      loggerFactory,
      ProcessingTimeout(),
    )
    val token = Await.result(authTokenSource.getToken, 30.seconds)

    RemoteParticipantConfig(adminApi, ledgerApi.clientConfig, token)
  }

  def remoteParticipantConfigWithAdminToken: RemoteParticipantConfig =
    RemoteParticipantConfig(adminApi, ledgerApi.clientConfig, ledgerApi.authConfig.adminToken)
}
