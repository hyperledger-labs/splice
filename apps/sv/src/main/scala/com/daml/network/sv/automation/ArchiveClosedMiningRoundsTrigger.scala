package com.daml.network.sv.automation

import cats.syntax.traverse.*
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
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.health.HealthReporting.implicitPrettyString
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ArchiveClosedMiningRoundsTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CoinLedgerConnection,
    waitForUnclaimedRewardsToBeExpired: Boolean,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      ArchiveClosedMiningRoundsTrigger.CreateArchiveClosedMiningRoundConfirmationTask
    ] {

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

  private def maybeCreateTask(
      closedRound: Contract[ClosedMiningRound.ContractId, ClosedMiningRound]
  ): Future[
    Option[ArchiveClosedMiningRoundsTrigger.CreateArchiveClosedMiningRoundConfirmationTask]
  ] = {
    for {
      appRewardCoupons <- store.listAppRewardCoupons(closedRound.payload.round.number, Some(1))
      validatorRewardCoupons <- store.listValidatorRewardCoupons(
        closedRound.payload.round.number,
        Some(1),
      )
      coinRules <- store.getCoinRules()
      action = coinRulesArchiveMiningRoundAction(
        coinRules.contractId,
        closedRound.contractId,
      )
      confirmationQueryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
    } yield {
      if (
        // if there are unclaimed rewards left in this round
        (waitForUnclaimedRewardsToBeExpired && (appRewardCoupons.length + validatorRewardCoupons.length > 0)) ||
        // ... or a confirmation to archive was already created by this SV
        confirmationQueryResult.value.isDefined
      ) {
        None
      } else {
        Some(
          ArchiveClosedMiningRoundsTrigger.CreateArchiveClosedMiningRoundConfirmationTask(
            closedRound,
            confirmationQueryResult.offset,
          )
        )
      }
    }
  }

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[
    Seq[ArchiveClosedMiningRoundsTrigger.CreateArchiveClosedMiningRoundConfirmationTask]
  ] = {
    for {
      acs <- store.defaultAcs
      rounds <- acs.listContracts(cc.round.ClosedMiningRound.COMPANION)
      tasks <- rounds.traverse(maybeCreateTask)
    } yield tasks.flatten {
      case None => Seq()
      case Some(task) => Seq(task)
    }
  }

  override protected def completeTask(
      task: ArchiveClosedMiningRoundsTrigger.CreateArchiveClosedMiningRoundConfirmationTask
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      closedRound = task.closedRound
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
          commandId = CoinLedgerConnection.CommandId(
            "com.daml.network.sv.createMiningRoundArchiveConfirmation",
            Seq(svParty, svcParty),
            closedRound.contractId.contractId,
          ),
          deduplicationOffset = task.confirmationTxOffset,
          domainId = domainId,
        )
        .map(_._1)
      acs <- store.defaultAcs
      _ <- acs.signalWhenIngestedOrShutdown(txOffset)
    } yield {
      TaskSuccess(
        s"Successfully created a confirmation for archiving closed mining round ${closedRound.payload.round.number}"
      )
    }
  }

  override protected def isStaleTask(
      task: ArchiveClosedMiningRoundsTrigger.CreateArchiveClosedMiningRoundConfirmationTask
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      acs <- store.defaultAcs
      // lookup closed mining round once again in the ACS to check if it was archived
      closedRoundExists <- acs
        .lookupContractById(cc.round.ClosedMiningRound.COMPANION)(
          task.closedRound.contractId
        )
        .map(_.isDefined)
      isStale <-
        if (!closedRoundExists)
          Future.successful(true)
        else
          existsClosedRoundArchivalConfirmation(task.closedRound.contractId)
    } yield isStale
  }
}

object ArchiveClosedMiningRoundsTrigger {
  case class CreateArchiveClosedMiningRoundConfirmationTask(
      closedRound: Contract[ClosedMiningRound.ContractId, ClosedMiningRound],
      confirmationTxOffset: String,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("closedRound", _.closedRound),
        param("confirmationTxOffset", _.confirmationTxOffset),
      )
    }
  }
}
