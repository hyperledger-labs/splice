// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.FullClientConfig
import com.digitalasset.canton.participant.config.{BaseParticipantConfig, RemoteParticipantConfig}

trait BaseParticipantClientConfig extends BaseParticipantConfig {

  def adminApi: ClientConfigWithAuth
  def ledgerApi: ClientConfigWithAuth

  override def clientAdminApi: FullClientConfig = adminApi.clientConfig
  override def clientLedgerApi: FullClientConfig = ledgerApi.clientConfig
  override def httpHealthClientConfig = None

  def participantClientConfigWithAdminToken: RemoteParticipantConfig =
    RemoteParticipantConfig(
      adminApi.clientConfig,
      ledgerApi.clientConfig,
      token = ledgerApi.authConfig.adminToken,
      adminApiToken = adminApi.authConfig.adminToken,
    )
}

/** Configuration to connect the console to a participant running remotely.
  *
  * @param adminApi the configuration to connect the console to the remote admin api
  * @param ledgerApi the configuration to connect the console to the remote ledger api
  */
case class ParticipantClientConfig(
    override val adminApi: ClientConfigWithAuth,
    override val ledgerApi: ClientConfigWithAuth,
) extends BaseParticipantClientConfig
