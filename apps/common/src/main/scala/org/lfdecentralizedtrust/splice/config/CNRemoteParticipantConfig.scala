// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.FullClientConfig
import com.digitalasset.canton.participant.config.{BaseParticipantConfig, RemoteParticipantConfig}

abstract class BaseParticipantClientConfig(
    val adminApi: FullClientConfig,
    val ledgerApi: LedgerApiClientConfig,
) extends BaseParticipantConfig {
  override def clientAdminApi: FullClientConfig = adminApi
  override def clientLedgerApi: FullClientConfig = ledgerApi.clientConfig

  def participantClientConfigWithAdminToken: RemoteParticipantConfig =
    RemoteParticipantConfig(adminApi, ledgerApi.clientConfig, ledgerApi.authConfig.adminToken)
}

/** Configuration to connect the console to a participant running remotely.
  *
  * @param adminApi the configuration to connect the console to the remote admin api
  * @param ledgerApi the configuration to connect the console to the remote ledger api
  */
case class ParticipantClientConfig(
    override val adminApi: FullClientConfig,
    override val ledgerApi: LedgerApiClientConfig,
) extends BaseParticipantClientConfig(adminApi, ledgerApi)
