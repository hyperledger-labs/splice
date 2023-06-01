package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.Vote
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExecuteVoteRequestActionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[Vote.ContractId, Vote](
      svTaskContext.svcStore,
      Vote.COMPANION,
    )
    with SvTaskBasedTrigger[ReadyContract[Vote.ContractId, Vote]] {

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      voteContract: ReadyContract[Vote.ContractId, Vote]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val voteRequestId = voteContract.contract.payload.requestCid
    for {
      svcRulesVotes <- store.lookupVoteRequest(voteRequestId)
      isStale = svcRulesVotes.isEmpty
      action = svcRulesVotes.map(_.payload.action)
      result <-
        if (isStale)
          Future.successful(
            TaskSuccess(
              s"Skipping as the $action has already been executed"
            )
          )
        else
          for {
            domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
            svcRules <- store.getSvcRules()
            requiredNumVotes = SvUtil.requiredNumVotes(svcRules)
            votes <- store.listEligibleVotes(voteRequestId)
            acceptVotes = votes.count(_.payload.accept)
            taskOutcome <-
              if (acceptVotes >= requiredNumVotes) {
                val cmd = svcRules.contractId.exerciseSvcRules_ExecuteDefiniteVote(
                  voteRequestId,
                  votes
                    .map(_.contractId)
                    .asJava,
                )
                svTaskContext.connection
                  .submitWithResultNoDedup(
                    Seq(store.key.svParty),
                    Seq(store.key.svcParty),
                    cmd,
                    domainId,
                  )
                  .map(_ =>
                    TaskSuccess(
                      s"executed the action $action as there are ${votes.size} vote(s) which is >=" +
                        s" the required $requiredNumVotes votes."
                    )
                  )
              } else
                Future.successful(
                  TaskSuccess(
                    s"not yet executing $action," +
                      s" as there are only ${votes.size} out of" +
                      s" the required $requiredNumVotes votes."
                  )
                )
          } yield taskOutcome
    } yield result
  }

  override def completeTaskAsFollower(
      voteContract: ReadyContract[Vote.ContractId, Vote]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(
        s"ignoring ${PrettyContractId(voteContract.contract)}, as we're not the leader"
      )
    )
  }

}
