// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.config.PeriodicBackupDumpConfig
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.util.BackupDump

import java.nio.file.Paths
import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.{Failure, Success}

/** As taking a topology snapshot is not a cheap operation, we limit this trigger to produce at most one snapshot per day.
  */
class PeriodicTopologySnapshotTrigger(
    synchronizerAlias: SynchronizerAlias,
    config: PeriodicBackupDumpConfig,
    triggerContext: TriggerContext,
    sequencerAdminConnection: SequencerAdminConnection,
    participantAdminConnection: ParticipantAdminConnection,
    clock: Clock,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PeriodicTaskTrigger(config.backupInterval, triggerContext) {

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit traceContext: TraceContext): Future[TaskOutcome] = {
    participantAdminConnection
      .getSynchronizerId(synchronizerAlias)
      .transformWith {
        case Failure(s: StatusRuntimeException) if s.getStatus.getCode == Status.Code.NOT_FOUND =>
          Future.successful(TaskNoop)
        case Failure(e) => Future.failed(e)
        case Success(synchronizerId) =>
          val utcDate = ZonedDateTime.now(ZoneOffset.UTC).toLocalDate.toString
          val folderName = s"topology_snapshot_$utcDate"
          for {
            snapshotExists <- checkTopologySnapshot(folderName)
            res <-
              if (!snapshotExists)
                takeTopologySnapshot(sequencerAdminConnection, folderName, utcDate, synchronizerId)
              else Future.successful(TaskSuccess("Today's topology snapshot already exists."))
          } yield res
      }
  }

  private def checkTopologySnapshot(folderName: String): Future[Boolean] =
    for {
      res <- Future {
        blocking {
          BackupDump.bucketExists(config.location, s"$folderName/genesis-state", loggerFactory)
        }
      }
    } yield res

  private def takeTopologySnapshot(
      sequencerAdminConnection: SequencerAdminConnection,
      folderName: String,
      utcDate: String,
      synchronizerId: SynchronizerId,
  )(implicit traceContext: TraceContext): Future[TaskSuccess] =
    for {
      sequencerId <- sequencerAdminConnection.getSequencerId
      // uses onboardingStateV2 so we don't lose information when exporting
      onboardingState <- sequencerAdminConnection.getOnboardingState(sequencerId)
      authorizedStore <- sequencerAdminConnection.exportAuthorizedStoreSnapshot(sequencerId.uid)
      // list a summary of the transactions state at the time of the snapshot to validate further imports
      summary <- sequencerAdminConnection.getTopologyTransactionsSummary(
        TopologyStoreId.Synchronizer(synchronizerId),
        clock.now,
      )
      _ <- Future {
        blocking {
          val fileDesc =
            s"dumping current topology state into gcp bucket"
          logger.debug(s"Attempting to write $fileDesc")
          val paths = Seq(
            BackupDump.writeBytes(
              config.location,
              Paths.get(s"$folderName/genesis-state"),
              onboardingState.toByteArray,
              loggerFactory,
            ),
            BackupDump.writeBytes(
              config.location,
              Paths.get(s"$folderName/authorized"),
              authorizedStore.toByteArray,
              loggerFactory,
            ),
            BackupDump.write(
              config.location,
              Paths.get(s"$folderName/transactions-summary"),
              summary.toString,
              loggerFactory,
            ),
          )
          logger.debug(s"Wrote $fileDesc")
          paths
        }
      }
    } yield TaskSuccess(s"Took a new topology snapshot for $utcDate")
}
