// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import cats.syntax.option.*
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.TopologyChangeOp
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}

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
      mappings <- participantAdminConnection
        .listPartyToParticipant(
          store = TopologyStoreId.Synchronizer(synchronizerId).some,
          operation = Some(TopologyChangeOp.Replace),
          filterParticipant = participantId.toProtoPrimitive,
          filterParty = dsoParty.toProtoPrimitive,
        )
    } yield {
      logger.info("DSO party mappings to our participant: " + mappings.map(_.mapping))
      mappings.nonEmpty
    }

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
    participantAdminConnection
      .getDsoPartyToParticipantTransaction(domain, participantId, dsoParty)
      .fold(
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

}

object DsoPartyHosting {

  sealed trait DsoPartyMigrationFailure

  final case class RequiredProposalNotFound(
      partyToParticipantSerial: PositiveInt
  ) extends DsoPartyMigrationFailure
}
