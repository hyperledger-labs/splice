package com.daml.network.sv.automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.{CoinRules, CoinRules_MiningRound_Archive}
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CoinRules
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ArchiveClosedMiningRoundsTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      QueryResult[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]]
    ] {
  private type Task = QueryResult[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]]

  private val svParty = store.key.svParty
  private val svcParty = store.key.svcParty

  private def coinRulesArchiveMiningRoundAction(
      coinRulesCid: CoinRules.ContractId,
      closedRoundCid: ClosedMiningRound.ContractId,
  ): ActionRequiringConfirmation =
    new ARC_CoinRules(
      coinRulesCid,
      new CRARC_MiningRound_Archive(
        new CoinRules_MiningRound_Archive(
          closedRoundCid
        )
      ),
    )

  private def existsClosedRoundArchivalConfirmation(
      closedRoundId: ClosedMiningRound.ContractId
  ): Future[Boolean] = {
    for {
      coinRules <- store.getCoinRules()
      action = coinRulesArchiveMiningRoundAction(
        coinRules.contractId,
        closedRoundId,
      )
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
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      closedRound = task.value
      action = coinRulesArchiveMiningRoundAction(
        coinRules.contractId,
        closedRound.contractId,
      )
      update = svcRules.contractId
        .exerciseSvcRules_ConfirmAction(
          svParty.toProtoPrimitive,
          action,
        )
      txOffset <- connection
        .submitWithResultAndOffset(
          actAs = Seq(svParty),
          readAs = Seq(svcParty),
          update = update,
          commandId = CNLedgerConnection.CommandId(
            "com.daml.network.sv.createMiningRoundArchiveConfirmation",
            Seq(svParty, svcParty),
            closedRound.contractId.contractId,
          ),
          deduplicationOffset = task.deduplicationOffset,
          domainId = domainId,
        )
        .map(_._1)
      _ <- store.multiDomainAcsStore.signalWhenIngestedOrShutdown(domainId, txOffset)
    } yield {
      TaskSuccess(
        s"Successfully created a confirmation for archiving closed mining round ${closedRound.payload.round.number}"
      )
    }
  }

  override protected def isStaleTask(
      task: Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      closedRound = task.value
      // lookup closed mining round once again in the ACS to check if it was archived
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
