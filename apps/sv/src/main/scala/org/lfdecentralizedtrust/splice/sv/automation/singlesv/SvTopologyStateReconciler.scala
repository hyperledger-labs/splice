// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.ledger.javaapi.data.codegen.ContractCompanion
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.store.DsoRulesStore.DsoRulesWithSvNodeStates
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvTopologyStatePollingAndAssignedTrigger.{
  StreamedAssignedContract,
  TaskTrigger,
  Tick,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import pprint.Tree

import scala.concurrent.{ExecutionContext, Future}

trait DsoRulesTopologyStateReconciler[Task] {

  protected def svDsoStore: SvDsoStore

  def diffDsoRulesWithTopology(
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[Task]] = {
    svDsoStore.getDsoRulesWithSvNodeStates().flatMap(diffDsoRulesWithTopology)
  }

  protected def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesWithSvNodeStates
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[Task]]

  def reconcileTask(
      task: Task
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[TaskOutcome]
}

/** Trigger that runs both on a regular interval and if the DsoRules contract is assigned.
  * It reconciles the DsoRules contract with the topology state.
  * This is done to ensure that we have minimum latency in applying any changes to the topology state (by using the assigned contract source)
  * and to also ensure that the changes are eventually applied if currently not possible (by using the regular ticking interval)
  */
abstract class SvTopologyStatePollingAndAssignedTrigger[Task](
    originalContext: TriggerContext,
    store: SvDsoStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SourceBasedTrigger[TaskTrigger] {

  // set parallelism to 1 as a full reconciliation is run in a single call so we don't want to run multiple reconciliations in parallel
  protected lazy val noParallelismContext: TriggerContext =
    originalContext.focus(_.config.parallelism).replace(1)
  override protected lazy val context: TriggerContext = noParallelismContext

  val reconciler: DsoRulesTopologyStateReconciler[Task]
  protected val contractsToRunOn
      : Seq[ContractCompanion.WithoutKey[DsoRules.Contract, DsoRules.ContractId, DsoRules]] = Seq(
    DsoRules.COMPANION
  )

  override protected def source(implicit traceContext: TraceContext): Source[TaskTrigger, NotUsed] =
    Source
      .tick(
        context.config.pollingInterval.asFiniteApproximation,
        context.config.pollingInterval.asFiniteApproximation,
        Tick,
      )
      .mergeAll[TaskTrigger](
        contractsToRunOn.map(
          store.multiDomainAcsStore.streamAssignedContracts(_).map(StreamedAssignedContract.apply)
        ),
        eagerComplete = false,
      )
      .conflate((source, _) => source)
      .mapMaterializedValue(_ => NotUsed)

  override protected def isStaleTask(task: TaskTrigger)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    store.lookupDsoRules().map(_.isEmpty)

  override protected def completeTask(task: TaskTrigger)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    reconciler
      .diffDsoRulesWithTopology()
      .flatMap { tasks =>
        if (tasks.nonEmpty) {
          logger.info(s"Reconciling tasks: $tasks")
        }
        tasks
          .parTraverse(task =>
            withSpan("reconcile_task") { implicit tc => _ =>
              reconciler.reconcileTask(task)
            }
          )
          .map(outcomes => {
            if (outcomes.nonEmpty) {
              TaskSuccess(s"Tasks reconciled: $outcomes")
            } else TaskNoop
          })
      }
  }

}

object SvTopologyStatePollingAndAssignedTrigger {
  sealed trait TaskTrigger extends PrettyPrinting
  case object Tick extends TaskTrigger {
    override def pretty: Pretty[Tick.this.type] = _ => Tree.Literal("Tick")
  }
  case class StreamedAssignedContract(contract: AssignedContract[?, ?]) extends TaskTrigger {
    override def pretty: Pretty[StreamedAssignedContract.this.type] =
      prettyOfClass(
        param("contract", _.contract)
      )
  }
}
