package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredSvOnboardingRequestTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cn.svonboarding.SvOnboardingRequest.ContractId,
      cn.svonboarding.SvOnboardingRequest,
    ](
      store.multiDomainAcsStore,
      store.listExpiredSvOnboardingRequests,
      cn.svonboarding.SvOnboardingRequest.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[ReadyContract[
      cn.svonboarding.SvOnboardingRequest.ContractId,
      cn.svonboarding.SvOnboardingRequest,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[
    ReadyContract[
      cn.svonboarding.SvOnboardingRequest.ContractId,
      cn.svonboarding.SvOnboardingRequest,
    ]
  ]

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId.exerciseSvcRules_ExpireSvOnboardingRequest(
        co.work.contract.contractId
      )
      _ <- connection.submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = co.work.domain,
      )
    } yield TaskSuccess("archived expired SV onboarding contract")

  override def completeTaskAsFollower(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(
        show"ignoring expired ${PrettyContractId(co.work.contract)}, as we're not the leader"
      )
    )
  }

  override def isLeader()(implicit tc: TraceContext): Future[Boolean] = store.svIsLeader()
}
