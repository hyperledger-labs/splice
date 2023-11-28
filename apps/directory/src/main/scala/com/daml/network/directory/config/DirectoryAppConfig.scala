package com.daml.network.directory.config

import com.daml.network.config.*
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class DirectoryAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CNDbConfig,
    svUser: String,
    override val participantClient: CNParticipantClientConfig,
    scanClient: ScanAppClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    parameters: CNNodeParametersConfig = CNNodeParametersConfig(batching = BatchingConfig()),
) extends CNNodeBackendConfig {
  override val nodeTypeName: String = "directory"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class DirectoryAppClientConfig(
    ledgerApiUser: String,
    adminApi: NetworkAppClientConfig,
    ledgerApi: CNLedgerApiClientConfig,
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

case class DirectoryAppExternalClientConfig(
    adminApi: NetworkAppClientConfig,
    ledgerApiUser: String,
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
