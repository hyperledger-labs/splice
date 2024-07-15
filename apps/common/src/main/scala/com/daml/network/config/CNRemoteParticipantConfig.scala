// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.config

import org.apache.pekko.actor.ActorSystem
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.participant.config.{BaseParticipantConfig, RemoteParticipantConfig}

/** Configuration to connect the console to a participant running remotely.
  *
  * @param adminApi the configuration to connect the console to the remote admin api
  * @param ledgerApi the configuration to connect the console to the remote ledger api
  */
case class ParticipantClientConfig(
    adminApi: ClientConfig,
    ledgerApi: LedgerApiClientConfig,
) extends BaseParticipantConfig {
  override def clientAdminApi: ClientConfig = adminApi
  override def clientLedgerApi: ClientConfig = ledgerApi.clientConfig

  def getParticipantClientConfig()(implicit actorSystem: ActorSystem): RemoteParticipantConfig = {
    val tokenStrO = ledgerApi.getToken().map(_.accessToken)
    RemoteParticipantConfig(adminApi, ledgerApi.clientConfig, tokenStrO)
  }

  def participantClientConfigWithAdminToken: RemoteParticipantConfig =
    RemoteParticipantConfig(adminApi, ledgerApi.clientConfig, ledgerApi.authConfig.adminToken)
}
