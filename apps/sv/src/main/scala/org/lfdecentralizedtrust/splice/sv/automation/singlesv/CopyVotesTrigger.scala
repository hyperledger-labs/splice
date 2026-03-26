// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import ScheduledTaskTrigger.ReadyTask
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  Reason,
  SvInfo,
  Vote,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.sv.SvApp
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.Contract

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
) extends ScheduledTaskTrigger[CopyVotesTrigger.Task]() {

  import CopyVotesTrigger.*

  private val thisSvParty = store.key.svParty.toProtoPrimitive

  private def getThisSvName(svs: java.util.Map[String, SvInfo]): Option[String] =
    svs.asScala.get(thisSvParty).map(_.name)

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Task]] =
    for {
      dsoRules <- store.getDsoRules()
      sourceSvExists = SvApp.isSvName(sourceSvName, dsoRules.contract)
      thisSvNameOpt = getThisSvName(dsoRules.contract.payload.svs)
      tasks <- (sourceSvExists, thisSvNameOpt) match {
        case (false, _) =>
          logger.warn(
            s"Source SV '$sourceSvName' not found in DsoRules.svs; cannot copy votes"
          )
          Future.successful(Seq.empty)
        case (_, None) =>
          logger.warn(
            s"This SV party '$thisSvParty' not found in DsoRules.svs; cannot copy votes"
          )
          Future.successful(Seq.empty)
        case (true, Some(myName)) =>
          store.listVoteRequests().map { requests =>
            requests
              .flatMap { voteRequest =>
                val votes = voteRequest.payload.votes.asScala
                val sourceVoteOpt = votes.get(sourceSvName)
                val thisSvHasVoted = votes.contains(myName)
                sourceVoteOpt match {
                  case Some(sourceVote) if !thisSvHasVoted =>
                    Seq(Task(voteRequest, sourceVote))
                  case _ =>
                    Seq.empty
                }
              }
              .take(limit)
          }
      }
    } yield tasks

  override protected def completeTask(
      task: ReadyTask[Task]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val trackingCid = task.work.voteRequest.payload.trackingCid.toScala
      .getOrElse(task.work.voteRequest.contractId)
    for {
      dsoRules <- store.getDsoRules()
      resolvedVoteRequest <- store.getVoteRequest(trackingCid)
      reason = new Reason(
        task.work.sourceVote.reason.url,
        s"Copied from $sourceSvName: ${task.work.sourceVote.reason.body}",
      )
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_CastVote(
          resolvedVoteRequest.contractId,
          new Vote(
            thisSvParty,
            task.work.sourceVote.accept,
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
      s"Copied ${if (task.work.sourceVote.accept) "accept"
        else "reject"} vote from $sourceSvName on vote request ${task.work.voteRequest.contractId}"
    )
  }

  override protected def isStaleTask(
      task: ReadyTask[Task]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore.containsArchived(
      Seq(task.work.voteRequest.contractId)
    )
}

object CopyVotesTrigger {
  final case class Task(
      voteRequest: Contract[VoteRequest.ContractId, VoteRequest],
      sourceVote: Vote,
  ) extends PrettyPrinting {

    override protected def pretty: Pretty[this.type] = prettyOfClass(
      param("voteRequest", _.voteRequest),
      param("sourceVoteAccept", (t: Task) => (t.sourceVote.accept: Boolean)),
    )
  }
}
