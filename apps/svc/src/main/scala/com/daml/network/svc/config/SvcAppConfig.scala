package com.daml.network.svc.config

import com.daml.network.config.{
  AutomationConfig,
  CNNodeBackendConfig,
  CNParticipantClientConfig,
  GrpcCNNodeClientConfig,
}
import com.digitalasset.canton.config.*

case class SvcAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    ledgerApiUser: String,
    participantClient: CNParticipantClientConfig,
    domains: SvcDomainConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    // TODO(M3-07): use price from SvcRules
    // TODO(M3-46): remove entirely once mining rounds automations moves into SvApp
    coinPrice: BigDecimal = 1.0,
) extends CNNodeBackendConfig {
  override val nodeTypeName: String = "SVC"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class SvcAppClientConfig(
    adminApi: ClientConfig
) extends GrpcCNNodeClientConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
