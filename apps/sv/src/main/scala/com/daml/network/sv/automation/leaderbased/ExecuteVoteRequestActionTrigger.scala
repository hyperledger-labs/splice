package com.daml.network.sv.automation.leaderbased

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.Vote
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.AssignedContract
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
) extends OnAssignedContractTrigger.Template[Vote.ContractId, Vote](
      svTaskContext.svcStore,
      Vote.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[Vote.ContractId, Vote]] {

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      voteContract: AssignedContract[Vote.ContractId, Vote]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val voteRequestId = voteContract.payload.requestCid
    for {
      svcRulesVotes <- store.lookupVoteRequest(voteRequestId)
      action = svcRulesVotes.map(_.payload.action)
      isStale = svcRulesVotes.isEmpty
      result <-
        if (isStale)
          Future.successful(
            TaskSuccess(
              s"Skipping as the $action has already been executed"
            )
          )
        else
          for {
            svcRules <- store.getSvcRules()
            requiredNumVotes = SvUtil.requiredNumVotes(svcRules)
            votes <- store.listEligibleVotes(voteRequestId)
            acceptVotes = votes.count(_.payload.accept == true)
            rejectVotes = votes.count(_.payload.accept == false)
            taskOutcome <-
              if (acceptVotes >= requiredNumVotes || rejectVotes >= requiredNumVotes) {
                for {
                  coinRules <- store.getCoinRules()
                  coinRulesId = coinRules.contractId
                  cmd = svcRules.exercise(
                    _.exerciseSvcRules_ExecuteDefiniteVote(
                      voteRequestId,
                      java.util.Optional.of(coinRulesId),
                      votes
                        .map(_.contractId)
                        .asJava,
                    )
                  )
                  _ <- svTaskContext.connection
                    .submit(
                      Seq(store.key.svParty),
                      Seq(store.key.svcParty),
                      cmd,
                    )
                    .noDedup
                    .yieldResult()
                } yield TaskSuccess(
                  s"executed the action $action as there are ${votes.size} vote(s) which is >=" +
                    s" the required $requiredNumVotes votes."
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
}
