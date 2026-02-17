// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class UpdateToLatestSchemaVersionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[UpdateToLatestSchemaVersionTrigger.Task]
    with SvTaskBasedTrigger[
      UpdateToLatestSchemaVersionTrigger.Task
    ] {
import UpdateToLatestSchemaVersionTrigger.Task
  private val store = svTaskContext.dsoStore

  private val maxVersion = 1L

  protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = {
    for {
      amuletRulesO <- store.lookupAmuletRules()
      supports24hSubmissionDelay <- svTaskContext.packageVersionSupport.supports24hSubmissionDelay(Seq(store.key.dsoParty, store.key.svParty), context.clock.now)
    } yield {
      amuletRulesO.toList.filter(c =>
        c.contract.payload.contractStateSchemaVersion.toScala.fold(true)(_ < maxVersion) && supports24hSubmissionDelay.supported
      ).map(Task(_))
    }
  }

  override protected def completeTaskAsDsoDelegate(
      task: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      miningRoundTriple <- store.getOpenMiningRoundTriple()
      damlRoundTriple = new splice.amuletrules.OpenMiningRoundTriple(
        miningRoundTriple.oldest.contractId,
        miningRoundTriple.middle.contractId,
        miningRoundTriple.newest.contractId,
      )
      update = dsoRules.exercise(
        _.exerciseDsoRules_AmuletRules_UpdateToLatestSchemaVersion(
          task.amuletRules.contractId,
          new splice.amuletrules.AmuletRules_UpdateToLatestSchemaVersion(
            damlRoundTriple
          ),
          controller,
        )
      )
      result <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Medium)
        .submit(
          actAs = Seq(store.key.svParty),
          readAs = Seq(store.key.dsoParty),
          update = update,
        )
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"Updated to version ${result.exerciseResult.result.newContractStateSchemaVersion}"
    )
  }

  override protected def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore.lookupContractById(splice.amuletrules.AmuletRules.COMPANION)(task.amuletRules.contractId).map(_.isEmpty)
}

object UpdateToLatestSchemaVersionTrigger {
  case class Task(
    amuletRules: AssignedContract[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("amuletRules", _.amuletRules),
    )
  }
}
