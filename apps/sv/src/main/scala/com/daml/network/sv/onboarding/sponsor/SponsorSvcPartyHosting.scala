package com.daml.network.sv.onboarding.sponsor

import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.sv.onboarding.SvcPartyHosting
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

class SponsorSvcPartyHosting(
    participantAdminConnection: ParticipantAdminConnection,
    svcParty: PartyId,
    svcPartyHosting: SvcPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  def authorizeSvcPartyToParticipant(
      domain: DomainId,
      participantId: ParticipantId,
      svcSize: Int,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[Instant] =
    for {
      _ <- participantAdminConnection.ensurePartyToParticipant(
        domain,
        svcParty,
        participantId,
        signedBy,
        svcSize,
      )
      authorizedAt <- svcPartyHosting.waitForSvcPartyToParticipantAuthorization(
        domain,
        participantId,
      )
    } yield {
      logger.info(
        s"Party $svcParty is authorized on participant $participantId"
      )
      authorizedAt
    }

}
