// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.{
  PeriodicTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.config.PeriodicTopologySnapshotConfig
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.util.TopologySnapshot

import java.nio.file.{Path, Paths}
import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.{ExecutionContext, Future, blocking}

class PeriodicTopologySnapshotTrigger(
    config: PeriodicTopologySnapshotConfig,
    triggerContext: TriggerContext,
    sequencerAdminConnection: SequencerAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PeriodicTaskTrigger(config.backupInterval, triggerContext) {

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit traceContext: TraceContext): Future[TaskOutcome] = {
    val utcDate = ZonedDateTime.now(ZoneOffset.UTC).toLocalDate.toString
    val folderName = s"topology_snapshot_$utcDate"
    for {
      snapshotMissing <- checkTopologySnapshot(s"$folderName/genesis-state")
      res <-
        if (snapshotMissing) takeTopologySnapshot(sequencerAdminConnection, folderName, utcDate)
        else Future.successful(TaskSuccess("Today's topology snapshot already exists."))
    } yield res
  }

  private def checkTopologySnapshot(fileName: String): Future[Boolean] =
    for {
      res <- Future {
        blocking {
          TopologySnapshot.fileExists(config.location, fileName, loggerFactory)
        }
      }
    } yield res

  private def takeTopologySnapshot(
      sequencerAdminConnection: SequencerAdminConnection,
      folderName: String,
      utcDate: String,
  )(implicit traceContext: TraceContext): Future[TaskSuccess] =
    for {
      sequencerId <- sequencerAdminConnection.getSequencerId
      onboardingState <- sequencerAdminConnection.getOnboardingState(sequencerId)
      authorizedStore <- sequencerAdminConnection.exportAuthorizedStoreSnapshot(sequencerId.uid)
      _ <- Future {
        blocking {
          val genesisStateFilename = Paths.get(s"$folderName/genesis-state")
          val authorizedFilename = Paths.get(s"$folderName/authorized")
          val fileDesc =
            s"dumping current topology state into gcp bucket"
          logger.debug(s"Attempting to write $fileDesc")
          val paths = Seq(
            TopologySnapshot.write(
              config.location,
              genesisStateFilename,
              onboardingState.toStringUtf8,
              loggerFactory,
            ),
            TopologySnapshot.write(
              config.location,
              authorizedFilename,
              authorizedStore.toStringUtf8,
              loggerFactory,
            ),
          )
          logger.info(s"Wrote $fileDesc")
          paths
        }
      }
    } yield TaskSuccess(s"Took a new topology snapshot for $utcDate")
}
