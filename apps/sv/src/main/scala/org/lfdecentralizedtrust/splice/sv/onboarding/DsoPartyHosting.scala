// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import cats.data.OptionT
import cats.syntax.option.*
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.{PartyToParticipant, TopologyChangeOp}
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class used to orchestrate the flow of DSO Party hosting on SV dedicated participant.
  */
class DsoPartyHosting(
    participantAdminConnection: ParticipantAdminConnection,
    dsoParty: PartyId,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  def isDsoPartyAuthorizedOn(
      synchronizerId: SynchronizerId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      mappings <-
        listActivePartyToParticipantMappings(dsoParty, synchronizerId, Some(participantId))
    } yield {
      logger.info("DSO party mappings to our participant: " + mappings.map(_.mapping))
      mappings.nonEmpty
    }

  private def listActivePartyToParticipantMappings(
      party: PartyId,
      domain: SynchronizerId,
      participantId: Option[ParticipantId],
      timeQuery: TimeQuery = TimeQuery.HeadState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipant]]] =
    participantAdminConnection
      .listPartyToParticipant(
        store = TopologyStoreId.Synchronizer(domain).some,
        operation = Some(TopologyChangeOp.Replace),
        filterParticipant = participantId.fold("")(_.toProtoPrimitive),
        filterParty = party.toProtoPrimitive,
        timeQuery = timeQuery,
      )

  // Wait for party to participant authorization to be reflected from the TopologyAdminCommand.ListPartyToParticipant
  // It is used in both candidate and sponsor side to ensure the party to participant are added successfully.
  // It returns the timestamp when the authorization becomes valid.
  def waitForDsoPartyToParticipantAuthorization(
      domain: SynchronizerId,
      participantId: ParticipantId,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Instant] = retryProvider.retry(
    retryFor,
    "wait_dso_party_authorization",
    "wait for DSO party to participant authorization to complete",
    getDsoPartyToParticipantTransaction(domain, participantId).fold(
      throw Status.NOT_FOUND
        .withDescription(
          show"Authorization to $participantId is still in progress"
        )
        .asRuntimeException()
    ) { mapping =>
      logger.debug(show"the party to participant authorization $mapping has been observed")
      mapping.base.validFrom
    },
    logger,
  )

  /** Return the transaction that first added the participant to PartyToParticipant
    * if the participant is still included in the latest state.
    */
  private def getDsoPartyToParticipantTransaction(
      domain: SynchronizerId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): OptionT[Future, TopologyResult[PartyToParticipant]] =
    OptionT(for {
      // We only fetch transactions for the DSO party so one per SV on/offboarding which
      // we expect to be rare so we can fetch the entire history.
      xs <- listActivePartyToParticipantMappings(
        dsoParty,
        domain,
        None,
        TimeQuery.Range(None, None),
      )
    } yield {
      // topology read service _should_ sort this but given that we assume everything
      // fits in memory we may as well go for the extra safeguard.
      xs.sortBy(_.base.serial).foldLeft[Option[TopologyResult[PartyToParticipant]]](None) {
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

object DsoPartyHosting {

  sealed trait DsoPartyMigrationFailure

  final case class RequiredProposalNotFound(
      partyToParticipantSerial: PositiveInt
  ) extends DsoPartyMigrationFailure
}
