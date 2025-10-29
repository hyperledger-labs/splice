// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import cats.data.EitherT
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.migration.AcsExporter.{AcsExportFailure, AcsExportForParties}
import org.lfdecentralizedtrust.splice.util.SynchronizerMigrationUtil

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class AcsExporter(
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    enableNewExport: Boolean,
    val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  private val domainStateTopology = new SynchronizerParametersStateTopologyConnection(
    participantAdminConnection
  )
  def exportAcsAtTimestamp(
      domain: SynchronizerId,
      timestamp: Instant,
      force: Boolean,
      parties: AcsExportForParties,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[ByteString]] = {
    logger.info(s"Exporting ACS for $parties")
    getPartiesForWhichToExport(domain, parties).flatMap(parties =>
      participantAdminConnection.downloadAcsSnapshot(
        parties = parties,
        filterSynchronizerId = Some(domain),
        timestamp = Some(timestamp),
        force = force,
      )
    )
  }

  def safeExportParticipantPartiesAcsFromPausedDomain(
      domain: SynchronizerId
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): EitherT[Future, AcsExportFailure, (Seq[ByteString], Instant)] = {
    for {
      parties <- EitherT.liftF(
        getPartiesForWhichToExport(domain, AcsExportForParties.AllParticipantParties)
      )
      _ = logger.info(
        "Exporting ACS for all the parties hosted on the participant for paused synchronizer"
      )
      paramsState <- domainStateTopology
        .firstAuthorizedStateForTheLatestSynchronizerParametersState(domain)
        .toRight(AcsExporter.DomainStateNotFound)
      _ <- EitherT.cond[Future](
        SynchronizerMigrationUtil.synchronizerIsPaused(paramsState.currentState),
        (),
        AcsExporter.DomainNotPaused,
      )
      _ <- EitherT.liftF[Future, AcsExportFailure, Unit](
        waitUntilSynchronizerTime(domain, paramsState.acsExportWaitTimestamp)
      )
      snapshot <- EitherT.liftF[Future, AcsExportFailure, Seq[ByteString]](
        participantAdminConnection.downloadAcsSnapshot(
          parties = parties,
          filterSynchronizerId = Some(domain),
          timestamp = Some(paramsState.exportTimestamp),
          force = true,
        )
      )
    } yield {
      snapshot -> paramsState.exportTimestamp
    }
  }

  private def getPartiesForWhichToExport(
      syncId: SynchronizerId,
      forParties: AcsExportForParties,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Set[PartyId]] = {
    forParties match {
      case AcsExportForParties.AllParticipantParties =>
        if (enableNewExport)
          Set.empty[PartyId].pure[Future]
        else {
          for {
            participantId <- participantAdminConnection.getId()
            parties <- participantAdminConnection
              .listPartyToParticipant(
                store = TopologyStoreId.SynchronizerStore(syncId).some,
                filterParticipant = participantId.toProtoPrimitive,
              )
              .map(_.map(_.mapping.partyId).toSet)
          } yield parties
        }
      case AcsExportForParties.OnlyForParties(parties) => parties.pure[Future]
    }
  }

  private def waitUntilSynchronizerTime(
      synchronizerId: SynchronizerId,
      timestamp: Instant,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ) = {
    for {
      _ <- retryProvider
        .waitUntil(
          RetryFor.WaitingOnInitDependency,
          "wait_safe",
          "wait for mediator and participant response time after domain is paused",
          participantAdminConnection
            // This is an interactive call, and we'd rather not wait a full polling interval for it.
            .getDomainTimeLowerBound(
              synchronizerId,
              maxDomainTimeLag = NonNegativeFiniteDuration.ofSeconds(1),
            )
            .map(domainTimeResponse => {
              val domainTimeLowerBound = domainTimeResponse.timestamp.toInstant
              if (domainTimeLowerBound.isBefore(timestamp)) {
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    s"we should wait until $timestamp to let all participants catch up with the paused domain state. Current domain time is $domainTimeLowerBound"
                  )
                  .asRuntimeException()
              }
            }),
          logger,
        )
    } yield ()
  }

}

object AcsExporter {

  sealed trait AcsExportForParties

  object AcsExportForParties {
    case object AllParticipantParties extends AcsExportForParties
    case class OnlyForParties(parties: Set[PartyId]) extends AcsExportForParties
  }

  sealed trait AcsExportFailure

  case object DomainNotPaused extends AcsExportFailure
  case object DomainStateNotFound extends AcsExportFailure
}
