package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class CompletedSvOnboardingTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      SvcRules.ContractId,
      SvcRules,
    ](
      svcStore,
      SvcRules.COMPANION,
    )
    with SvTaskBasedTrigger[ReadyContract[
      SvcRules.ContractId,
      SvcRules,
    ]] {
  type SvcRulesContract = ReadyContract[
    SvcRules.ContractId,
    SvcRules,
  ]

  override def completeTaskAsLeader(
      svcRules: SvcRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svOnboardings <- svcStore.listSvOnboardingsBySvcMembers(svcRules.contract)
      cmds = svOnboardings.map(co =>
        svcRules.contract.contractId
          .exerciseSvcRules_ArchiveSvOnboarding(co.contractId)
      )
      _ <- Future.sequence(
        cmds.map(cmd =>
          connection.submitCommandsNoDedup(
            Seq(svcStore.key.svParty),
            Seq(svcStore.key.svcParty),
            commands = cmd.commands.asScala.toSeq,
            domainId = svcRules.domain,
          )
        )
      )
    } yield TaskSuccess(
      show"Archived ${cmds.size} `SvOnboarding` contract(s) as the SV(s) are added to SVC."
    )
  }

  override def completeTaskAsFollower(
      svcRules: SvcRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring ${PrettyContractId(svcRules.contract)}, as we're not the leader")
    )
  }

  override protected def isLeader(): Future[Boolean] = svcStore.svIsLeader()
}
