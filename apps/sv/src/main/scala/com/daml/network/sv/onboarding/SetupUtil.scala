// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.onboarding

import com.daml.network.environment.{
  BaseLedgerConnection,
  SpliceLedgerConnection,
  ParticipantAdminConnection,
  RetryFor,
}
import com.daml.network.sv.config.SvAppBackendConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

private[onboarding] object SetupUtil {

  def setupSvParty(
      connection: BaseLedgerConnection,
      config: SvAppBackendConfig,
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit
      traceContext: TraceContext
  ): Future[PartyId] = {
    val partyHint = config.svPartyHint.getOrElse(
      config.onboarding
        .getOrElse(
          sys.error("Cannot setup SV party without either party hint or an onboarding config")
        )
        .name
    )
    connection.ensureUserPrimaryPartyIsAllocated(
      config.ledgerApiUser,
      partyHint,
      participantAdminConnection,
    )
  }

  def ensureDsoPartyMetadataAnnotation(
      connection: SpliceLedgerConnection,
      config: SvAppBackendConfig,
      dsoParty: PartyId,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] =
    connection.ensureUserMetadataAnnotation(
      config.ledgerApiUser,
      BaseLedgerConnection.DSO_PARTY_USER_METADATA_KEY,
      dsoParty.toProtoPrimitive,
      RetryFor.WaitingOnInitDependency,
    )

  // We just need readAs for command submissions since they always act as
  // the SV party but reassignments for contracts with only the DSO as a stakeholder
  // require actAs.
  // The PartyToParticipant topology mapping will still prevent any command submission
  // that tries to actAs=DSO if there is > 1 SV. This is tested in
  // https://github.com/DACH-NY/canton-network-node/blob/96268016b57344c70e54133a97998a73245aa6b0/apps/app/src/test/scala/com/daml/network/integration/tests/SvOnboardingAddlIntegrationTest.scala#L350
  def grantSvUserRightActAsDso(
      connection: SpliceLedgerConnection,
      user: String,
      dso: PartyId,
  )(implicit tc: TraceContext): Future[Unit] = {
    connection.grantUserRights(
      user,
      Seq(dso),
      Seq.empty,
    )
  }
}
