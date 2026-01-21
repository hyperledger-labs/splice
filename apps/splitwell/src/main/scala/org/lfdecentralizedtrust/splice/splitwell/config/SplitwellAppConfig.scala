// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.config

import org.lfdecentralizedtrust.splice.config.{
  AutomationConfig,
  SpliceBackendConfig,
  SpliceParametersConfig,
  ParticipantClientConfig,
  SynchronizerConfig,
  HttpClientConfig,
  NetworkAppClientConfig,
}
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.digitalasset.canton.config.*

case class SplitwellSynchronizerConfig(
    splitwell: SplitwellDomains
)

// Domains used for splitwell operations. There is one preferred domain
// which will be chosen by default if possible, e.g., for new group creations.
// and a list of other domains that will eventually be phased out.
// During an upgrade the preferred domain will be switched to the new domain
// while the old domain will be added to others.
case class SplitwellDomains(
    preferred: SynchronizerConfig,
    others: Seq[SynchronizerConfig],
)

case class SplitwellAppBackendConfig(
    override val adminApi: AdminServerConfig = AdminServerConfig(),
    override val storage: DbConfig,
    providerUser: String,
    participantClient: ParticipantClientConfig,
    scanClient: ScanAppClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    // TODO(DACH-NY/canton-network-node#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long = 0L,
    domains: SplitwellSynchronizerConfig,
    parameters: SpliceParametersConfig = SpliceParametersConfig(batching = BatchingConfig()),
    requiredDarVersion: PackageVersion = DarResources.splitwell.latest.metadata.version,
) extends SpliceBackendConfig // TODO(DACH-NY/canton-network-node#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "splitwell"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class SplitwellAppClientConfig(
    // Admin API for reads.
    adminApi: NetworkAppClientConfig,
    // Ledger API for writes.
    participantClient: ParticipantClientConfig,
    scanClient: ScanAppClientConfig,
    ledgerApiUser: String,
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
