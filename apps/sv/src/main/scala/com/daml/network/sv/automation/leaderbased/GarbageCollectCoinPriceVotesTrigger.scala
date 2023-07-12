package com.daml.network.sv.automation.leaderbased

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class GarbageCollectCoinPriceVotesTrigger(
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

  val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      svcRules: SvcRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      coinPriceVotes <- store.listAllCoinPriceVotes()
      (memberVotes, nonMemberVotes) = coinPriceVotes.partition(v =>
        svcRules.contract.payload.members.asScala.contains(v.payload.sv)
      )
      nonMemberVoteCids = nonMemberVotes.map(_.contractId)
      memberDuplicatedVoteCids =
        memberVotes
          .groupBy(_.payload.sv)
          .values
          .filter(_.size > 1)
          .map(_.map(_.contractId).asJava)
          .toSeq
      _ <-
        if (nonMemberVoteCids.nonEmpty || memberDuplicatedVoteCids.nonEmpty) {
          val cmd = svcRules.contract.contractId
            .exerciseSvcRules_GarbageCollectCoinPriceVotes(
              nonMemberVoteCids.asJava,
              memberDuplicatedVoteCids.asJava,
            )
          svTaskContext.connection.submitCommandsNoDedup(
            Seq(store.key.svParty),
            Seq(store.key.svcParty),
            commands = cmd.commands.asScala.toSeq,
            domainId = svcRules.domain,
          )
        } else Future.successful(())
    } yield TaskSuccess(
      s"Archived ${nonMemberVoteCids.size} non member votes and deduplicated votes for ${memberDuplicatedVoteCids.size} SVs"
    )
  }
}
