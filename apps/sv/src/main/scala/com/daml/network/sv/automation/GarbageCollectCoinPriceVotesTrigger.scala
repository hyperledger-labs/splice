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

class GarbageCollectCoinPriceVotesTrigger(
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
      coinPriceVotes <- svcStore.listAllCoinPriceVotes()
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
          connection.submitCommandsNoDedup(
            Seq(svcStore.key.svParty),
            Seq(svcStore.key.svcParty),
            commands = cmd.commands.asScala.toSeq,
            domainId = svcRules.domain,
          )
        } else Future.successful(())
    } yield TaskSuccess(
      s"Archived ${nonMemberVoteCids.size} non member votes and deduplicated votes for ${memberDuplicatedVoteCids.size} SVs"
    )
  }

  override def completeTaskAsFollower(
      svcRules: SvcRulesContract
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring ${PrettyContractId(svcRules.contract)}, as we're not the leader")
    )
  }

  override protected def isLeader()(implicit tc: TraceContext): Future[Boolean] =
    svcStore.svIsLeader()
}
