package com.daml.network.sv.migration

import cats.data.EitherT
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor, RetryProvider}
import com.daml.network.environment.TopologyAdminConnection.TopologyResult
import com.daml.network.sv.migration.AcsExporter.AcsExportFailure
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.topology.transaction.DomainParametersStateX
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

  def exportAcsAtTimestamp(domain: DomainId, timestamp: Instant, parties: PartyId*)(implicit
      tc: TraceContext
  ): Future[ByteString] = {
    participantAdminConnection.downloadAcsSnapshot(
      parties = parties.toSet,
      filterDomainId = Some(domain),
      timestamp = Some(timestamp),
    )
  }

  def safeExportAcsFromPausedDomain(domain: DomainId, parties: PartyId*)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): EitherT[Future, AcsExportFailure, ByteString] = {
    for {
      domainParamsStateTopology <- EitherT
        .liftF[Future, AcsExportFailure, TopologyResult[DomainParametersStateX]](
          participantAdminConnection.getDomainParametersState(domain)
        )
      domainParamsState = domainParamsStateTopology.mapping.parameters
      _ <- EitherT.cond[Future](
        domainParamsState.maxRatePerParticipant == NonNegativeInt.zero,
        (),
        AcsExporter.DomainNotPaused,
      )
      _ <- EitherT.liftF[Future, AcsExportFailure, Unit](
        waitForMediatorAndParticipantResponseTime(domain, domainParamsStateTopology)
      )
      snapshot <- EitherT.liftF[Future, AcsExportFailure, ByteString](
        participantAdminConnection.downloadAcsSnapshot(
          parties = parties.toSet,
          filterDomainId = Some(domain),
          timestamp = Some(domainParamsStateTopology.base.validFrom),
          force = true,
        )
      )
    } yield {
      snapshot
    }
  }

  private def waitForMediatorAndParticipantResponseTime(
      domainId: DomainId,
      domainParamsTopology: TopologyResult[DomainParametersStateX],
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ) = {
    val domainParams = domainParamsTopology.mapping.parameters
    val duration = domainParams.mediatorReactionTimeout.duration
      .plus(domainParams.participantResponseTimeout.duration)
      .plus(5.seconds.toJava) // a small buffer to account for network latency

    val readyForDumpAfter = domainParamsTopology.base.validFrom.plus(duration)
    for {
      _ <- retryProvider
        .waitUntil(
          RetryFor.WaitingOnInitDependency,
          "wait for mediator and participant response time after domain is paused",
          participantAdminConnection
            .getDomainTime(domainId, retryProvider.timeouts.default)
            .map(domainTimeResponse =>
              if (domainTimeResponse.timestamp.toInstant.isBefore(readyForDumpAfter)) {
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    s"we should wait until $readyForDumpAfter to let all participants catch up with the paused domain state"
                  )
                  .asRuntimeException()
              }
            ),
          logger,
        )
    } yield ()
  }

}

object AcsExporter {

  sealed trait AcsExportFailure

  case object DomainNotPaused extends AcsExportFailure
}
