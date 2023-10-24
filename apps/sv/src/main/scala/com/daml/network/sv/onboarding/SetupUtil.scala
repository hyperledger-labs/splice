package com.daml.network.sv.onboarding

import com.daml.network.environment.{CNLedgerConnection, ParticipantAdminConnection}
import com.daml.network.sv.config.SvAppBackendConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

private[onboarding] object SetupUtil {

  def setupSvParty(
      connection: CNLedgerConnection,
      config: SvAppBackendConfig,
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext): Future[PartyId] = {
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

  def ensureSvcPartyMetadataAnnotation(
      connection: CNLedgerConnection,
      config: SvAppBackendConfig,
      svcParty: PartyId,
  )(implicit ec: ExecutionContext): Future[Unit] = connection.ensureUserMetadataAnnotation(
    config.ledgerApiUser,
    CNLedgerConnection.SVC_PARTY_USER_METADATA_KEY,
    svcParty.toProtoPrimitive,
  )
}
