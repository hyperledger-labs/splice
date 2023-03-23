package com.daml.network.splitwell.config

import com.digitalasset.canton.DomainAlias
import com.daml.network.config.{
  AutomationConfig,
  CNRemoteParticipantConfig,
  LocalCNNodeConfig,
  RemoteCNNodeConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.config.*

case class SplitwellDomainConfig(
    global: DomainAlias,
    splitwell: SplitwellDomains,
)

// Domains used for splitwell operations. There is one preferred domain
// which will be chosen by default if possible, e.g., for new group creations.
// and a list of other domains that will eventually be phased out.
// During an upgrade the preferred domain will be switched to the new domain
// while the old domain will be added to others.
case class SplitwellDomains(
    preferred: DomainAlias,
    others: Seq[DomainAlias],
)

case class SplitwellAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    providerUser: String,
    remoteParticipant: CNRemoteParticipantConfig,
    remoteScan: ScanAppClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    domains: SplitwellDomainConfig,
) extends LocalCNNodeConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "splitwell"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class SplitwellAppClientConfig(
    // Admin API for reads.
    adminApi: ClientConfig,
    // Ledger API for writes.
    remoteParticipant: CNRemoteParticipantConfig,
    remoteScan: ScanAppClientConfig,
    ledgerApiUser: String,
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
