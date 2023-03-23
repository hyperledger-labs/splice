package com.daml.network.config

import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.participant.config.{BaseParticipantConfig, RemoteParticipantConfig}

/** Configuration to connect the console to a participant running remotely.
  *
  * @param adminApi the configuration to connect the console to the remote admin api
  * @param ledgerApi the configuration to connect the console to the remote ledger api
  */
case class CNRemoteParticipantConfig(
    adminApi: ClientConfig,
    ledgerApi: CNLedgerApiClientConfig,
) extends BaseParticipantConfig {
  override def clientAdminApi: ClientConfig = adminApi
  override def clientLedgerApi: ClientConfig = ledgerApi.clientConfig

  def getRemoteParticipantConfig(): RemoteParticipantConfig = {
    val token = ledgerApi.getToken()
    RemoteParticipantConfig(adminApi, ledgerApi.clientConfig, token)
  }

  def remoteParticipantConfigWithAdminToken: RemoteParticipantConfig =
    RemoteParticipantConfig(adminApi, ledgerApi.clientConfig, ledgerApi.authConfig.adminToken)
}
