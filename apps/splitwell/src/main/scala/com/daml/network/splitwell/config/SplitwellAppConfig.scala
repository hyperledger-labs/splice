package com.daml.network.splitwell.config

import com.daml.network.config.{
  AutomationConfig,
  CNNodeBackendConfig,
  CNNodeParametersConfig,
  CNParticipantClientConfig,
  DomainConfig,
  HttpCNNodeClientConfig,
  NetworkAppClientConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class SplitwellDomainConfig(
    splitwell: SplitwellDomains
)

// Domains used for splitwell operations. There is one preferred domain
// which will be chosen by default if possible, e.g., for new group creations.
// and a list of other domains that will eventually be phased out.
// During an upgrade the preferred domain will be switched to the new domain
// while the old domain will be added to others.
case class SplitwellDomains(
    preferred: DomainConfig,
    others: Seq[DomainConfig],
)

case class SplitwellAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    providerUser: String,
    participantClient: CNParticipantClientConfig,
    scanClient: ScanAppClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    domains: SplitwellDomainConfig,
    parameters: CNNodeParametersConfig = CNNodeParametersConfig(batching = BatchingConfig()),
) extends CNNodeBackendConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "splitwell"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class SplitwellAppClientConfig(
    // Admin API for reads.
    adminApi: NetworkAppClientConfig,
    // Ledger API for writes.
    participantClient: CNParticipantClientConfig,
    scanClient: ScanAppClientConfig,
    ledgerApiUser: String,
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
