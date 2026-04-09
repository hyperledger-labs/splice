// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.lsu

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.networking
import com.digitalasset.canton.topology.transaction.GrpcConnection
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import java.net.URI
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.{
  DsoSequencer,
  RollForwardLsu,
}
import scala.concurrent.{ExecutionContext, Future}

final case class RollForwardLsuTrigger(
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: ScanConnection,
    alias: SynchronizerAlias,
    override protected val context: TriggerContext,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends PollingParallelTaskExecutionTrigger[RollForwardLsu] {

  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[RollForwardLsu]] = {
    scanConnection.lookupRollForwardLsu().flatMap {
      case None => Future.successful(Seq.empty)
      case Some(rollForward) =>
        participantAdminConnection.listConnectedDomains().flatMap { connections =>
          connections
            .find(_.physicalSynchronizerId == rollForward.currentPhysicalSynchronizerId) match {
            case None => Future.successful(Seq.empty)
            case Some(_) =>
              getUsableNewSequencers(alias, rollForward).map(_.map(_ => rollForward).toList)
          }
        }
    }
  }

  override protected def completeTask(
      task: RollForwardLsu
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      sequencers <- getUsableNewSequencers(alias, task).map(
        _.getOrElse(
          throw Status.FAILED_PRECONDITION
            .withDescription(s"Not enough sequencer connections for $alias")
            .asRuntimeException
        )
      )
      _ <- participantAdminConnection.performManualLsu(
        currentPsid = task.currentPhysicalSynchronizerId,
        successorPsid = task.successorPhysicalSynchronizerId,
        upgradeTime = Some(task.upgradeTime),
        sequencerSuccessors = sequencers.map(s => s.id -> getConnection(s)).toMap,
      )
    } yield TaskSuccess(s"Performed manual lsu for $task")

  override protected def isStaleTask(
      task: RollForwardLsu
  )(implicit tc: TraceContext): Future[Boolean] =
    for {
      connections <- participantAdminConnection.listConnectedDomains()
    } yield connections.find(_.physicalSynchronizerId == task.currentPhysicalSynchronizerId).isEmpty

  private def getUsableNewSequencers(alias: SynchronizerAlias, rollForward: RollForwardLsu)(implicit
      tc: TraceContext
  ): Future[Option[Seq[DsoSequencer]]] = {
    for {
      config <- participantAdminConnection
        .lookupSynchronizerConnectionConfig(alias)
        .map(
          _.getOrElse(
            throw Status.INTERNAL
              .withDescription(s"Failed to find connection config for ${alias}")
              .asRuntimeException
          )
        )
      sequencers <- scanConnection.listDsoSequencers()
    } yield {
      val newSequencers = sequencers
        .flatMap {
          case s if s.synchronizerId == rollForward.successorPhysicalSynchronizerId.logical =>
            s.sequencers
          case _ => Seq.empty
        }
        .filter(_.serial == Some(rollForward.successorPhysicalSynchronizerId.serial.unwrap.toLong))
      val oldSequencers =
        config.sequencerConnections.aliasToConnection.forgetNE.values.flatMap(_.sequencerId).toSet
      val usableNewSequencers = newSequencers.filter(s => oldSequencers.contains(s.id))
      if (usableNewSequencers.size >= config.sequencerConnections.sequencerTrustThreshold.unwrap) {
        Some(usableNewSequencers)
      } else {
        logger.warn(
          s"Found roll forward config $rollForward but available sequencers $usableNewSequencers is less than the trust threshold in the configuration ${config}"
        )
        None
      }
    }
  }

  private def getConnection(sequencer: DsoSequencer): GrpcConnection =
    networking.Endpoint
      .fromUris(NonEmpty(Seq, URI.create(sequencer.url)))
      .map { case (validatedEndpoints, useTls) =>
        GrpcConnection(validatedEndpoints, useTls, None)
      }
      .getOrElse(
        throw Status.INVALID_ARGUMENT
          .withDescription(s"Failed to create gRPC connection for ${sequencer.url}")
          .asRuntimeException()
      )
}
