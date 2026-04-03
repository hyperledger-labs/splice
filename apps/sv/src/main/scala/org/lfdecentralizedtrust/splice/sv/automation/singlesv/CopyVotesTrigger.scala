// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  Reason,
  SvInfo,
  Vote,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.sv.SvApp
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class CopyVotesTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    sourceSvName: String,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      VoteRequest.ContractId,
      VoteRequest,
    ](
      store,
      VoteRequest.COMPANION,
    ) {

  private val thisSvParty = store.key.svParty.toProtoPrimitive

  private def getThisSvName(svs: java.util.Map[String, SvInfo]): Option[String] =
    svs.asScala.get(thisSvParty).map(_.name)

  private def copiedReason(sourceVote: Vote): Reason =
    new Reason(
      sourceVote.reason.url,
      s"Automatically Copied from $sourceSvName: ${sourceVote.reason.body}",
    )

  private def shouldCopyVote(sourceVote: Vote, currentVote: Option[Vote]): Boolean =
    currentVote.forall { vote =>
      vote.accept != sourceVote.accept ||
      vote.reason.url != sourceVote.reason.url ||
      vote.reason.body != copiedReason(sourceVote).body
    }

  override def completeTask(
      task: AssignedContract[VoteRequest.ContractId, VoteRequest]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      dsoRules <- store.getDsoRules()
      sourceSvExists = SvApp.isSvName(sourceSvName, dsoRules.contract)
      thisSvNameOpt = getThisSvName(dsoRules.contract.payload.svs)
      outcome <- (sourceSvExists, thisSvNameOpt) match {
        case (false, _) =>
          logger.warn(
            s"Source SV '$sourceSvName' not found in DsoRules.svs; cannot copy votes"
          )
          Future.successful(TaskNoop)
        case (_, None) =>
          logger.warn(
            s"This SV party '$thisSvParty' not found in DsoRules.svs; cannot copy votes"
          )
          Future.successful(TaskNoop)
        case (true, Some(myName)) =>
          val votes = task.payload.votes.asScala
          val sourceVoteOpt = votes.get(sourceSvName)
          val thisSvVote = votes.get(myName)
          sourceVoteOpt match {
            case Some(sourceVote) if shouldCopyVote(sourceVote, thisSvVote) =>
              val trackingCid = task.payload.trackingCid.toScala
                .getOrElse(task.contractId)
              for {
                resolvedVoteRequest <- store.getVoteRequest(trackingCid)
                reason = copiedReason(sourceVote)
                cmd = dsoRules.exercise(
                  _.exerciseDsoRules_CastVote(
                    resolvedVoteRequest.contractId,
                    new Vote(
                      thisSvParty,
                      sourceVote.accept,
                      reason,
                      Optional.empty(),
                    ),
                  )
                )
                _ <- connection
                  .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
                  .noDedup
                  .yieldResult()
              } yield TaskSuccess(
                s"Copied ${if (sourceVote.accept) "accept"
                  else "reject"} vote from $sourceSvName on vote request ${task.contractId}"
              )
            case _ =>
              Future.successful(TaskNoop)
          }
      }
    } yield outcome
}
