package com.daml.network.sv.automation.confirmation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.CoinRules_MiningRound_Archive
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CoinRules
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.DedupOffset
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import ArchiveClosedMiningRoundsTrigger.*

class ArchiveClosedMiningRoundsTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {
  private val svParty = store.key.svParty
  private val svcParty = store.key.svcParty

  private def coinRulesArchiveMiningRoundAction(
      closedRoundCid: ClosedMiningRound.ContractId
  ): ActionRequiringConfirmation =
    new ARC_CoinRules(
      new CRARC_MiningRound_Archive(
        new CoinRules_MiningRound_Archive(
          closedRoundCid
        )
      )
    )

  private def existsClosedRoundArchivalConfirmation(
      closedRoundId: ClosedMiningRound.ContractId
  )(implicit tc: TraceContext): Future[Boolean] = {
    val action = coinRulesArchiveMiningRoundAction(
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
      svcRules <- store.getSvcRules()
      closedRound = task.value
      action = coinRulesArchiveMiningRoundAction(
        closedRound.contractId
      )
      update = svcRules.exercise(
        _.exerciseSvcRules_ConfirmAction(
          svParty.toProtoPrimitive,
          action,
        )
      )
      _ <- connection
        .submit(
          actAs = Seq(svParty),
          readAs = Seq(svcParty),
          update = update,
        )
        .withDedup(
          commandId = CNLedgerConnection.CommandId(
            "com.daml.network.sv.createMiningRoundArchiveConfirmation",
            Seq(svParty, svcParty),
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
      // archived or reassigned
      closedRoundExists <- store.multiDomainAcsStore
        .lookupContractByIdOnDomain(cc.round.ClosedMiningRound.COMPANION)(
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
