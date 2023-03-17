package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredSvOnboardingTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CoinLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      cn.svonboarding.SvOnboarding.ContractId,
      cn.svonboarding.SvOnboarding,
    ](
      store.defaultAcs,
      store.listExpiredSvOnboardings,
      cn.svonboarding.SvOnboarding.COMPANION,
    )
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[Contract[
      cn.svonboarding.SvOnboarding.ContractId,
      cn.svonboarding.SvOnboarding,
    ]]] {
  type Task = ScheduledTaskTrigger.ReadyTask[
    Contract[cn.svonboarding.SvOnboarding.ContractId, cn.svonboarding.SvOnboarding]
  ]

  override def completeTaskAsLeader(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      svcRules <- store.getSvcRules()
      cmd = svcRules.contractId.exerciseSvcRules_ExpireSvOnboarding(co.work.contractId)
      _ <- connection.submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        commands = cmd.commands.asScala.toSeq,
        domainId = domainId,
      )
    } yield TaskSuccess("archived expired SV onboarding contract")

  override def completeTaskAsFollower(co: Task)(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring expired ${PrettyContractId(co.work)}, as we're not the leader")
    )
  }

  override def isLeader(): Future[Boolean] = store.svIsLeader()
}
