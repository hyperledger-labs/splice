// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.splitwell.config

import com.daml.network.config.{
  AutomationConfig,
  CNDbConfig,
  CNNodeBackendConfig,
  CNNodeParametersConfig,
  CNParticipantClientConfig,
  SynchronizerConfig,
  HttpCNNodeClientConfig,
  NetworkAppClientConfig,
}
import com.daml.network.scan.config.ScanAppClientConfig
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
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CNDbConfig,
    providerUser: String,
    participantClient: CNParticipantClientConfig,
    scanClient: ScanAppClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long = 0L,
    domains: SplitwellSynchronizerConfig,
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
