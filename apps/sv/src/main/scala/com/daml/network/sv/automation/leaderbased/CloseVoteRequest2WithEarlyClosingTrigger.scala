package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_OffboardMember
import com.daml.network.codegen.java.cn.svcrules.VoteRequest2
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional

class CloseVoteRequest2WithEarlyClosingTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[VoteRequest2.ContractId, VoteRequest2](
      svTaskContext.svcStore,
      VoteRequest2.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[VoteRequest2.ContractId, VoteRequest2]] {

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      voteRequestContract: AssignedContract[VoteRequest2.ContractId, VoteRequest2]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val trackingCid =
      voteRequestContract.payload.trackingCid.toScala.getOrElse(voteRequestContract.contractId)
    for {
      currentRequest <- store.lookupVoteRequest2(trackingCid)
      action = currentRequest.map(_.payload.action)
      currentRequestCid = currentRequest.map(_.contractId).getOrElse(voteRequestContract.contractId)
      isStale = currentRequest.isEmpty
      result <-
        if (isStale)
          Future.successful(
            TaskSuccess(
              s"Skipping as the $action has already been executed."
            )
          )
        else {
          for {
            svcRules <- store.getSvcRules()
            votes = voteRequestContract.payload.votes.values().asScala
            defaultRequiredNumVotesForEarlyClosing = svcRules.payload.members.size()
            requiredNumVotesForEarlyClosing = voteRequestContract.payload.action match {
              case arcSvcRules: ARC_SvcRules =>
                arcSvcRules.svcAction match {
                  case action: SRARC_OffboardMember =>
                    if (
                      votes.map(_.sv).toSeq.contains(action.svcRules_OffboardMemberValue.member)
                    ) {
                      defaultRequiredNumVotesForEarlyClosing
                    } else {
                      defaultRequiredNumVotesForEarlyClosing - 1
                    }
                  case _ => defaultRequiredNumVotesForEarlyClosing
                }
              case _ => defaultRequiredNumVotesForEarlyClosing
            }
            taskOutcome <-
              if (votes.size >= requiredNumVotesForEarlyClosing) {
                for {
                  coinRules <- store.getCoinRules()
                  coinRulesId = coinRules.contractId
                  cmd = svcRules.exercise(
                    _.exerciseSvcRules_CloseVoteRequest2(
                      currentRequestCid,
                      java.util.Optional.of(coinRulesId),
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
                    s" the $requiredNumVotesForEarlyClosing votes required for early closing."
                )
              } else
                Future.successful(
                  TaskSuccess(
                    s"not yet executing the action $action," +
                      s" as there are only ${votes.size} out of" +
                      s" the $requiredNumVotesForEarlyClosing votes required for early closing."
                  )
                )
          } yield taskOutcome
        }
    } yield result
  }
}
