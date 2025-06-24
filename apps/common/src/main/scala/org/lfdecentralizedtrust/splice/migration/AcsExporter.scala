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
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.migration.AcsExporter.AcsExportFailure
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.topology.transaction.SynchronizerParametersState
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

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
      disasterRecovery: Boolean,
      parties: PartyId*
  )(implicit
      tc: TraceContext
  ): Future[ByteString] = {
    participantAdminConnection.downloadAcsSnapshotForSynchronizerMigration(
      parties = parties.toSet,
      synchronizerId = domain,
      timestamp = timestamp,
      disasterRecovery = disasterRecovery,
    )
  }

  def safeExportParticipantPartiesAcsFromPausedDomain(domain: SynchronizerId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): EitherT[Future, AcsExportFailure, (ByteString, Instant)] = {
    EitherT {
      for {
        participantId <- participantAdminConnection.getId()
        parties <- participantAdminConnection
          .listPartyToParticipant(
            store = TopologyStoreId.SynchronizerStore(domain).some,
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
  ): EitherT[Future, AcsExportFailure, (ByteString, Instant)] = {
    for {
      domainParamsStateTopology <- domainStateTopology
        .firstAuthorizedStateForTheLatestSynchronizerParametersState(domain)
        .toRight(AcsExporter.DomainStateNotFound)
      domainParamsState = domainParamsStateTopology.mapping.parameters
      _ <- EitherT.cond[Future](
        domainParamsState.confirmationRequestsMaxRate == NonNegativeInt.zero,
        (),
        AcsExporter.DomainNotPaused,
      )
      _ <- EitherT.liftF[Future, AcsExportFailure, Unit](
        waitForMediatorAndParticipantResponseTime(domain, domainParamsStateTopology)
      )
      acsSnapshotTimestamp = domainParamsStateTopology.base.validFrom
      snapshot <- EitherT.liftF[Future, AcsExportFailure, ByteString](
        participantAdminConnection.downloadAcsSnapshotForSynchronizerMigration(
          parties = parties.toSet,
          synchronizerId = domain,
          timestamp = acsSnapshotTimestamp,
          disasterRecovery = false,
        )
      )
    } yield {
      snapshot -> acsSnapshotTimestamp
    }
  }

  private def waitForMediatorAndParticipantResponseTime(
      synchronizerId: SynchronizerId,
      domainParamsTopology: TopologyResult[SynchronizerParametersState],
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ) = {
    val domainParams = domainParamsTopology.mapping.parameters
    val duration = domainParams.mediatorReactionTimeout.duration
      .plus(domainParams.confirmationResponseTimeout.duration)
      .plus(5.seconds.toJava) // a small buffer to account for network latency

    val readyForDumpAfter = domainParamsTopology.base.validFrom.plus(duration)
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
              if (domainTimeLowerBound.isBefore(readyForDumpAfter)) {
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    s"we should wait until $readyForDumpAfter to let all participants catch up with the paused domain state. Current domain time is $domainTimeLowerBound"
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
