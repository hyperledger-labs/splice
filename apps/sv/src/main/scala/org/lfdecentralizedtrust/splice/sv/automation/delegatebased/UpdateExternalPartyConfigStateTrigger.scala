// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.store.ExternalPartyConfigStateStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.{ExecutionContext, Future}

class UpdateExternalPartyConfigStateTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[UpdateExternalPartyConfigStateTrigger.Task]
    with SvTaskBasedTrigger[
      ScheduledTaskTrigger.ReadyTask[UpdateExternalPartyConfigStateTrigger.Task]
    ] {
  private val store = svTaskContext.dsoStore

  /** Retrieve a batch of tasks that are ready for execution now. */
  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[UpdateExternalPartyConfigStateTrigger.Task]] =
    (for {
      externalPartyConfigStatePair <- OptionT(store.lookupExternalPartyConfigStatesPair())
      if (externalPartyConfigStatePair.oldest.payload.targetArchiveAfter.isBefore(now.toInstant))
    } yield UpdateExternalPartyConfigStateTrigger.Task(externalPartyConfigStatePair)).value
      .map(_.toList)

  /** How to process a task. */
  override protected def completeTaskAsDsoDelegate(
      task: ScheduledTaskTrigger.ReadyTask[UpdateExternalPartyConfigStateTrigger.Task],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      triple <- store.getOpenMiningRoundTriple()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_UpdateExternalPartyConfigStates(
          amuletRules.contractId,
          task.work.externalPartyConfigStatePair.oldest.contractId,
          task.work.externalPartyConfigStatePair.newest.contractId,
          new splice.amuletrules.OpenMiningRoundTriple(
            triple.oldest.contractId,
            triple.middle.contractId,
            triple.newest.contractId,
          ),
          controller,
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.High)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          cmd,
        )
        .noDedup
        .yieldUnit()
    } yield TaskSuccess(
      s"successfully updated external party config states"
    )
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[UpdateExternalPartyConfigStateTrigger.Task]
  )(implicit tc: TraceContext): Future[Boolean] = {
    import cats.instances.future.*

    (for {
      _ <- MonadUtil.sequentialTraverse(task.work.externalPartyConfigStatePair.toSeq)(co =>
        OptionT(
          store.multiDomainAcsStore
            .lookupContractById(splice.externalpartyconfigstate.ExternalPartyConfigState.COMPANION)(
              co.contractId
            )
        )
      )
    } yield ()).isEmpty
  }
}

object UpdateExternalPartyConfigStateTrigger {
  case class Task(
      externalPartyConfigStatePair: ExternalPartyConfigStateStore.ExternalPartyConfigStatePair
  ) extends PrettyPrinting {

    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("externalPartyConfigStatePair", _.externalPartyConfigStatePair))
  }
}
