// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_MiningRound_Archive
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.ClosedMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupOffset
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

import ArchiveClosedMiningRoundsTrigger.*

class ArchiveClosedMiningRoundsTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {
  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty

  private def amuletRulesArchiveMiningRoundAction(
      closedRoundCid: ClosedMiningRound.ContractId
  ): ActionRequiringConfirmation =
    new ARC_AmuletRules(
      new CRARC_MiningRound_Archive(
        new AmuletRules_MiningRound_Archive(
          closedRoundCid
        )
      )
    )

  private def existsClosedRoundArchivalConfirmation(
      closedRoundId: ClosedMiningRound.ContractId
  )(implicit tc: TraceContext): Future[Boolean] = {
    val action = amuletRulesArchiveMiningRoundAction(
      closedRoundId
    )
    for {
      confirmationExists <- store
        .lookupConfirmationByActionWithOffset(svParty, action)
        .map(_.value.isDefined)
    } yield confirmationExists
  }

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    store.listArchivableClosedMiningRounds()
  }

  override protected def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      closedRound = task.value
      action = amuletRulesArchiveMiningRoundAction(
        closedRound.contractId
      )
      update = dsoRules.exercise(
        _.exerciseDsoRules_ConfirmAction(
          svParty.toProtoPrimitive,
          action,
        )
      )
      _ <- connection
        .submit(
          actAs = Seq(svParty),
          readAs = Seq(dsoParty),
          update = update,
        )
        .withDedup(
          commandId = SpliceLedgerConnection.CommandId(
            "org.lfdecentralizedtrust.splice.sv.createMiningRoundArchiveConfirmation",
            Seq(svParty, dsoParty),
            closedRound.contractId.contractId,
          ),
          deduplicationConfig = DedupOffset(task.offset),
        )
        .yieldUnit()
    } yield {
      TaskSuccess(
        s"Successfully created a confirmation for archiving closed mining round ${closedRound.payload.round.number}"
      )
    }
  }

  override protected def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    val closedRound = task.value
    val domainId = closedRound.domain
    for {
      // lookup closed mining round once again in the ACS to check if it was
      // archived or reassigned; if the latter, listArchivableClosedMiningRounds
      // can give us a corrected task with the new assignment
      closedRoundExists <- store.multiDomainAcsStore
        .lookupContractByIdOnDomain(splice.round.ClosedMiningRound.COMPANION)(
          domainId,
          closedRound.contractId,
        )
        .map(_.isDefined)
      isStale <-
        if (!closedRoundExists)
          Future.successful(true)
        else
          existsClosedRoundArchivalConfirmation(closedRound.contractId)
    } yield isStale
  }
}

object ArchiveClosedMiningRoundsTrigger {
  private[confirmation] type Task =
    QueryResult[AssignedContract[ClosedMiningRound.ContractId, ClosedMiningRound]]
}
