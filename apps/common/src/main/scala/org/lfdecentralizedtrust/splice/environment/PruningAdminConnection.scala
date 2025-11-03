// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.admin.api.client.commands.PruningSchedulerCommands
import com.digitalasset.canton.admin.api.client.data.{NodeStatus, PruningSchedule}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.PruningConfig

import scala.concurrent.{ExecutionContextExecutor, Future}

trait PruningAdminConnection {
  this: AppConnection & RetryProvider.Has =>
  protected implicit val ec: ExecutionContextExecutor
  type Status <: NodeStatus.Status
  protected val pruningCommands: PruningSchedulerCommands[_]

  private def setPruningSchedule(
      config: PruningConfig
  )(implicit tc: TraceContext): Future[Unit] =
    runCmd(pruningCommands.SetScheduleCommand(config.cron, config.maxDuration, config.retention))

  private def clearPruningSchedule(
  )(implicit tc: TraceContext): Future[Unit] =
    runCmd(pruningCommands.ClearScheduleCommand())

  def getPruningSchedule()(implicit tc: TraceContext): Future[Option[PruningSchedule]] =
    runCmd(pruningCommands.GetScheduleCommand())

  /** The schedule is specified in cron format and "max_duration" and "retention" durations. The cron string indicates
    *      the points in time at which pruning should begin in the GMT time zone, and the maximum duration indicates how
    *      long from the start time pruning is allowed to run as long as pruning has not finished pruning up to the
    *      specified retention period.
    */
  def ensurePruningSchedule(
      config: Option[PruningConfig]
  )(implicit tc: TraceContext): Future[Unit] =
    config match {
      case None =>
        retryProvider.ensureThatB(
          RetryFor.WaitingOnInitDependency,
          "pruning_schedule_remove",
          s"Pruning schedule is removed",
          getPruningSchedule().map(_.isEmpty),
          clearPruningSchedule(),
          logger,
        )
      case Some(c) =>
        retryProvider.ensureThatB(
          RetryFor.WaitingOnInitDependency,
          "pruning_schedule_set",
          s"Pruning schedule is set to $c",
          getPruningSchedule().map(scheduleO => scheduleO.contains(c.toSchedule)),
          setPruningSchedule(c),
          logger,
        )
    }

}
