package com.daml.network.sv.onboarding

import cats.data.OptionT
import com.daml.network.environment.RetryProvider.QuietNonRetryableException
import com.daml.network.environment.TopologyAdminConnection.TopologyResult
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.store.TimeQueryX
import com.digitalasset.canton.topology.transaction.{PartyToParticipantX, TopologyChangeOpX}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class used to orchestrate the flow of SVC Party hosting on SV dedicated participant.
  */
class SvcPartyHosting(
    participantAdminConnection: ParticipantAdminConnection,
    svcParty: PartyId,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  def isSvcPartyAuthorizedOn(
      domainId: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      mappings <-
        listActivePartyToParticipantMappings(svcParty, domainId, Some(participantId))
    } yield {
      logger.info("SVC party mappings to our participant: " + mappings.map(_.mapping))
      mappings.nonEmpty
    }

  private def listActivePartyToParticipantMappings(
      party: PartyId,
      domain: DomainId,
      participantId: Option[ParticipantId],
      timeQuery: TimeQueryX = TimeQueryX.HeadState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipantX]]] =
    participantAdminConnection
      .listPartyToParticipant(
        filterStore = domain.toProtoPrimitive,
        operation = Some(TopologyChangeOpX.Replace),
        filterParticipant = participantId.fold("")(_.toProtoPrimitive),
        filterParty = party.toProtoPrimitive,
        timeQuery = timeQuery,
      )

  // Wait for party to participant authorization to be reflected from the TopologyAdminCommand.ListPartyToParticipant
  // It is used in both candidate and sponsor side to ensure the party to participant are added successfully.
  // It returns the timestamp when the authorization becomes valid.
  def waitForSvcPartyToParticipantAuthorization(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Instant] = retryProvider.retryForClientCalls(
    "wait for svc party to participant authorization to complete",
    getSvcPartyToParticipantTransaction(domain, participantId).foldF(
      Future.failed(
        Status.NOT_FOUND
          .withDescription(
            show"Authorization to $participantId is still in progress"
          )
          .asRuntimeException()
      )
    ) { mapping =>
      val validFrom = mapping.base.validFrom
      logger.debug(show"the party to participant authorization $mapping has been observed")
      participantAdminConnection
        .waitForTopologyChangeToBeValid(
          show"SVC party to participant mapping to $participantId",
          validFrom,
        )
        .map(_ => validFrom)
    },
    logger,
  )

  /** Return the transaction that first added the participant to PartyToParticipant
    * if the participant is still included in the latest state.
    */
  private def getSvcPartyToParticipantTransaction(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): OptionT[Future, TopologyResult[PartyToParticipantX]] =
    OptionT(for {
      // We only fetch transactions for the svc party so one per SV on/offboarding which
      // we expect to be rare so we can fetch the entire history.
      xs <- listActivePartyToParticipantMappings(
        svcParty,
        domain,
        None,
        TimeQueryX.Range(None, None),
      )
    } yield {
      // topology read service _should_ sort this but given that we assume everything
      // fits in memory we may as well go for the extra safeguard.
      xs.sortBy(_.base.serial).foldLeft[Option[TopologyResult[PartyToParticipantX]]](None) {
        // Participant is no longer hosting the party
        case (_, newMapping) if !newMapping.mapping.participantIds.contains(participantId) => None
        // Participant starts hosting party
        case (None, newMapping) if newMapping.mapping.participantIds.contains(participantId) =>
          Some(newMapping)
        // Participant is hosting party but this is not the mapping that added it.
        case (Some(mapping), newMapping)
            if newMapping.mapping.participantIds.contains(participantId) =>
          Some(mapping)
        case _ => None
      }
    })

}

object SvcPartyHosting {

  sealed trait SvcPartyMigrationFailure

  final case class LockAcquireFailure(lockReason: String, cause: Throwable)
      extends SvcPartyMigrationFailure

  final case class RequiredProposalNotFound(
      partyToParticipantSerial: PositiveInt
  ) extends SvcPartyMigrationFailure

  case class PartyToParticipantProposalThresholdMismatch()
      extends QuietNonRetryableException(
        "Proposal must be recreated because the threshold has changed."
      )
}
