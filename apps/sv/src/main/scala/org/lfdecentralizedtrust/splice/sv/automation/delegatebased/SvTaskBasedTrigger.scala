// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.DelayUtil
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  RetryFor,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait SvTaskBasedTrigger[T <: PrettyPrinting] {
  this: TaskbasedTrigger[T] =>

  override final val taskRetry: RetryFor =
    RetryFor.Automation.copy(
      initialDelay = FiniteDuration
        .apply(svTaskContext.delegatelessAutomationExpectedTaskDuration, MILLISECONDS),
      maxDelay = FiniteDuration.apply(
        2 * svTaskContext.delegatelessAutomationExpectedTaskDuration,
        MILLISECONDS,
      ),
    )
  protected implicit def ec: ExecutionContext

  protected def svTaskContext: SvTaskBasedTrigger.Context

  private val store = svTaskContext.dsoStore

  final protected override def completeTask(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      sameEpoch = dsoRules.payload.epoch == svTaskContext.epoch
      svParty = store.key.svParty.toProtoPrimitive
      result <-
        if (sameEpoch) {
          completeTaskAsAnySv(task, svParty, dsoRules)
        } else {
          // TODO(DACH-NY/canton-network-internal#495) Could this be busy-looping as well, if we are a polling trigger?
          Future.successful(
            TaskSuccess(
              s"Skipping because current epoch ${dsoRules.payload.epoch} is not the same as trigger registration epoch ${svTaskContext.epoch}"
            )
          )
        }
    } yield result
  }

  private def completeTaskAsAnySv(
      task: T,
      svParty: String,
      dsoRules: AssignedContract[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    // default pollingInterval is 30 seconds
    val pollingTriggerInterval = context.config.pollingInterval.underlying.toMillis
    // Pick a random delay in Uniform~(0, upperBound) to avoid all nodes triggering at the same time
    // This formula ensures a uniform random delay across SVs that never exceeds the pollingTriggerInterval
    // and helps to distribute the load between nodes.
    val upperBound =
      Math.min(
        dsoRules.payload.svs
          .size()
          .toLong * svTaskContext.delegatelessAutomationExpectedTaskDuration,
        pollingTriggerInterval,
      )
    val delay = Random.nextLong(upperBound)
    // Check for staleness first so we can quickly move on to other tasks.
    // Otherwise we might block an execution slot for the wait time for a a stale task.
    // If tasks get produced faster than our wait time this will lead to falling further and further behind.
    isStaleTask(task).flatMap {
      if (_) {
        Future.successful(
          TaskSuccess(
            s"Skipping because task ${task.toString} is already completed"
          )
        )
      } else {
        DelayUtil
          .delayIfNotClosing(
            "dso-delegate-task-delay",
            FiniteDuration.apply(delay, TimeUnit.MILLISECONDS),
            this,
          )
          .onShutdown(logger.debug(s"Closing after waiting $delay ms"))
          .flatMap(_ =>
            // Check for staleness again, another SV may have completed it in the wait time.
            isStaleTask(task).flatMap {
              if (_) {
                Future.successful(
                  TaskSuccess(
                    s"Skipping because task ${task.toString} is already completed after waiting a delay of $delay ms"
                  )
                )
              } else {
                logger.info(
                  s"Completing dso delegate task ${task.toString} after waiting a delay of $delay ms"
                )
                completeTaskAsDsoDelegate(task, svParty)
              }
            }
          )
      }
    }
  }

  protected def completeTaskAsDsoDelegate(
      task: T,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome]

}

object SvTaskBasedTrigger {
  case class Context(
      dsoStore: SvDsoStore,
      connection: SpliceLedgerConnectionPriority => SpliceLedgerConnection,
      epoch: Long,
      delegatelessAutomationExpectedTaskDuration: Long,
      delegatelessAutomationExpiredRewardCouponBatchSize: Int,
      packageVersionSupport: PackageVersionSupport,
  )
}
