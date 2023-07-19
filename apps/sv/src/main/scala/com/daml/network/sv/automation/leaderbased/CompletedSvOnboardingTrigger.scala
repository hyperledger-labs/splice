package com.daml.network.sv.automation.leaderbased

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.util.ReadyContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

//TODO(#3756) reconsider this trigger
class CompletedSvOnboardingTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      SvcRules.ContractId,
      SvcRules,
    ](
      svTaskContext.svcStore,
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

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      svcRules: SvcRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svOnboardings <- store.listSvOnboardingRequestsBySvcMembers(svcRules)
      cmds = svOnboardings.map(co =>
        svcRules.contractId
          .exerciseSvcRules_ArchiveSvOnboardingRequest(co.contractId)
      )
      _ <- Future.sequence(
        cmds.map(cmd =>
          svTaskContext.connection.submitCommandsNoDedup(
            Seq(store.key.svParty),
            Seq(store.key.svcParty),
            commands = cmd.commands.asScala.toSeq,
            domainId = svcRules.domain,
          )
        )
      )
    } yield TaskSuccess(
      show"Archived ${cmds.size} `SvOnboardingRequest` contract(s) as the SV(s) are added to SVC."
    )
  }
}
