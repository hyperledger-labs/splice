// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.tracing.TraceContext
import io.circe.syntax.*
import io.opentelemetry.api.trace.Tracer
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.config.BackupDumpConfig
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.migration.SynchronizerNodeIdentities
import org.lfdecentralizedtrust.splice.util.BackupDump

import scala.concurrent.{blocking, ExecutionContextExecutor, Future}

final class BackupNodeIdentitiesTrigger(
    synchronizerAlias: SynchronizerAlias,
    dsoStore: SvDsoStore,
    backupConfig: BackupDumpConfig,
    participantAdminConnection: ParticipantAdminConnection,
    localSynchronizerNode: LocalSynchronizerNode,
    protected val context: TriggerContext,
)(implicit ec: ExecutionContextExecutor, override val tracer: Tracer, mat: Materializer)
    extends PollingParallelTaskExecutionTrigger[Unit] {

  private val taskCompleted: AtomicReference[Boolean] = new AtomicReference(false)

  protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Unit]] =
    if (taskCompleted.get()) {
      Future.successful(Seq.empty)
    } else {
      Future.successful(Seq(()))
    }
  protected def completeTask(task: Unit)(implicit tc: TraceContext): Future[TaskOutcome] = {
    val now = context.clock.now.toInstant
    val filename = Paths.get(
      s"sv_identities_${now}.json"
    )
    val fileDesc =
      s"node identities to ${backupConfig.locationDescription} at path: $filename"
    logger.info(
      s"Attempting to write $fileDesc"
    )
    for {
      identities <- SynchronizerNodeIdentities.getSynchronizerNodeIdentities(
        participantAdminConnection,
        localSynchronizerNode,
        dsoStore,
        synchronizerAlias,
        loggerFactory,
      )
      _ <- Future {
        val _ = blocking {
          BackupDump.write(
            backupConfig,
            filename,
            identities.toHttp().asJson.noSpaces,
            loggerFactory,
          )
        }
        logger.info(s"Wrote $fileDesc")
      }
      _ = taskCompleted.set(true)
    } yield TaskSuccess("Backup dumps created")
  }
  protected def isStaleTask(task: Unit)(implicit tc: TraceContext): Future[Boolean] =
    Future.successful(false)
}
