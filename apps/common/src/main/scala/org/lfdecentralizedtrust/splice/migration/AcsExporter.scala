// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import cats.data.EitherT
import cats.implicits.{catsSyntaxOptionId, showInterpolator}
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.migration.AcsExporter.AcsExportFailure
import org.lfdecentralizedtrust.splice.util.SynchronizerMigrationUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class AcsExporter(
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  private val domainStateTopology = new SynchronizerParametersStateTopologyConnection(
    participantAdminConnection
  )
  def exportAcsAtTimestamp(
      domain: SynchronizerId,
      timestamp: Instant,
      force: Boolean,
      parties: PartyId*
  )(implicit
      tc: TraceContext
  ): Future[Seq[ByteString]] = {
    participantAdminConnection.downloadAcsSnapshot(
      parties = parties.toSet,
      filterSynchronizerId = Some(domain),
      timestamp = Some(timestamp),
      force = force,
    )
  }

  def safeExportParticipantPartiesAcsFromPausedDomain(domain: SynchronizerId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): EitherT[Future, AcsExportFailure, (Seq[ByteString], Instant)] = {
    EitherT {
      for {
        participantId <- participantAdminConnection.getId()
        parties <- participantAdminConnection
          .listPartyToParticipant(
            store = TopologyStoreId.Synchronizer(domain).some,
            filterParticipant = participantId.toProtoPrimitive,
          )
          .map(_.map(_.mapping.partyId))
        _ = logger.info(show"Exporting ACS from paused domain $domain for $parties")
        acs <- safeExportAcsFromPausedDomain(domain, parties*).value
      } yield acs
    }
  }

  private def safeExportAcsFromPausedDomain(domain: SynchronizerId, parties: PartyId*)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): EitherT[Future, AcsExportFailure, (Seq[ByteString], Instant)] = {
    for {
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
          parties = parties.toSet,
          filterSynchronizerId = Some(domain),
          timestamp = Some(paramsState.exportTimestamp),
          force = true,
        )
      )
    } yield {
      snapshot -> paramsState.exportTimestamp
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

  sealed trait AcsExportFailure

  case object DomainNotPaused extends AcsExportFailure
  case object DomainStateNotFound extends AcsExportFailure
}
