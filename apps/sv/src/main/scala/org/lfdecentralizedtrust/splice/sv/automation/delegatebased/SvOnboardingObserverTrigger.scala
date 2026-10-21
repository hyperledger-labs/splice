// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.SvOnboardingConfirmed
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class SvOnboardingObserverTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SvOnboardingObserverTrigger.Task]
    with SvTaskBasedTrigger[SvOnboardingObserverTrigger.Task] {

  import SvOnboardingObserverTrigger.Task

  private val store = svTaskContext.dsoStore
  private val connection = svTaskContext.connection(SpliceLedgerConnectionPriority.Medium)

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      confirmations <- store.listSvOnboardingConfirmed()
      unobserved = confirmations.filter(co =>
        co.payload.svPartyIsObserver.toScala.contains(java.lang.Boolean.FALSE)
      )

      readyTasks <- MonadUtil.sequentialTraverse(unobserved) { c =>
        val partyId = PartyId.tryFromProtoPrimitive(c.payload.svParty)
        for {
          support <- svTaskContext.packageVersionSupport.supportsPermissionedSynchronizer(
            Seq(partyId),
            context.clock.now,
          )
        } yield {
          if (support.supported) Some(Task(c.contractId, partyId, support.packageIds))
          else None
        }
      }
    } yield readyTasks.flatten
  }

  override protected def completeTaskAsDsoDelegate(task: Task, svParty: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()

      cmd = dsoRules.exercise(
        _.exerciseDsoRules_MakeSvOnboardingConfirmedObserver(
          task.contractId,
          svParty,
        )
      )

      _ <- connection
        .submit(
          actAs = Seq(store.key.svParty),
          readAs = Seq(store.key.dsoParty),
          update = cmd,
        )
        .withPreferredPackage(task.preferredPackageIds)
        .noDedup
        .yieldUnit()

    } yield TaskSuccess(s"Made ${task.partyId} an observer of its onboarding confirmation")
  }

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    store.multiDomainAcsStore
      .lookupContractById(SvOnboardingConfirmed.COMPANION)(task.contractId)
      .map(_.isEmpty)
  }
}

object SvOnboardingObserverTrigger {
  final case class Task(
      contractId: SvOnboardingConfirmed.ContractId,
      partyId: PartyId,
      preferredPackageIds: Seq[String],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("contractId", _.contractId.contractId.unquoted),
        param("partyId", _.partyId),
        param("preferredPackageIds", _.preferredPackageIds.map(_.singleQuoted)),
      )
  }
}
