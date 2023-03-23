package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc.round.{ClosedMiningRound, SummarizingMiningRound}
import com.daml.network.codegen.java.cn.svcrules.Confirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_StartIssuing
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSv
import com.daml.network.codegen.java.cn.svonboarding.SvConfirmed
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.sv.SvApp.{isSvcMemberName, isSvcMemberParty}
import com.daml.network.util.Contract
import io.opentelemetry.api.trace.Tracer
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExecuteConfirmedActionTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger.Template[Confirmation.ContractId, Confirmation](
      store,
      () => store.domains.signalWhenConnected(store.defaultAcsDomain),
      Confirmation.COMPANION,
    )
    with SvTaskBasedTrigger[Contract[Confirmation.ContractId, Confirmation]] {

  override def completeTaskAsLeader(
      confirmationContract: Contract[Confirmation.ContractId, Confirmation]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val action = confirmationContract.payload.action
    isStaleAction(confirmationContract).flatMap { isStale =>
      if (isStale)
        Future.successful(
          TaskSuccess(
            show"Skipping as the confirmed $action has already been executed"
          )
        )
      else
        for {
          domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
          svcRules <- store.getSvcRules()
          requiredNumConfirmations = SvUtil.requiredNumConfirmations(svcRules)
          confirmations <- store.listConfirmations(action)
          uniqueConfirmations = confirmations.distinctBy(_.payload.confirmer)
          taskOutcome <-
            if (uniqueConfirmations.size >= requiredNumConfirmations) {
              val cmd = svcRules.contractId.exerciseSvcRules_ExecuteConfirmedAction(
                action,
                uniqueConfirmations
                  .map(_.contractId)
                  .asJava, // TODO(#3300) report duplicated and add test cases to make sure no duplicated confirmations here
              )
              connection
                .submitWithResultNoDedup(
                  Seq(store.key.svParty),
                  Seq(store.key.svcParty),
                  cmd,
                  domainId,
                )
                .map(_ =>
                  TaskSuccess(
                    show"executed an action $action as there are ${uniqueConfirmations.size} confirmation(s) which is >=" +
                      show" the required $requiredNumConfirmations confirmations."
                  )
                )
            } else
              Future.successful(
                TaskSuccess(
                  show"not yet executing $action," +
                    show" as there are only ${uniqueConfirmations.size} out of" +
                    show" the required $requiredNumConfirmations confirmations."
                )
              )
        } yield taskOutcome
    }
  }

  override def completeTaskAsFollower(
      confirmationContract: Contract[Confirmation.ContractId, Confirmation]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(show"ignoring ${PrettyContractId(confirmationContract)}, as we're not the leader")
    )
  }

  private def isStaleAction(
      confirmation: Contract[Confirmation.ContractId, Confirmation]
  ): Future[Boolean] = {
    store.defaultAcs.flatMap { acs =>
      // Add new cases as we port more triggers which require confirmation
      confirmation.payload.action match {
        case arcCoinRules: ARC_CoinRules =>
          arcCoinRules.coinRulesAction match {
            case startIssuingAction: CRARC_MiningRound_StartIssuing =>
              val sumRoundCid =
                startIssuingAction.coinRules_MiningRound_StartIssuingValue.miningRoundCid
              acs.lookupContractById(SummarizingMiningRound.COMPANION)(sumRoundCid).map(_.isEmpty)
            case archiveAction: CRARC_MiningRound_Archive =>
              val closedRoundCid =
                archiveAction.coinRules_MiningRound_ArchiveValue.closedRoundCid
              acs.lookupContractById(ClosedMiningRound.COMPANION)(closedRoundCid).map(_.isEmpty)
            case action =>
              throw new UnsupportedOperationException(
                show"coin rules $action is not yet supported"
              )
          }
        case arcSvcRules: ARC_SvcRules =>
          arcSvcRules.svcAction match {
            case confirmSvAction: SRARC_ConfirmSv =>
              for {
                isSvConfirmed <- acs
                  .findContract(SvConfirmed.COMPANION)(c =>
                    c.payload.svc == store.key.svcParty.toProtoPrimitive &&
                      c.payload.svParty == confirmSvAction.svcRules_ConfirmSvValue.newMemberParty
                  )
                  .map(_.nonEmpty)
                newMemberParty = PartyId.tryFromProtoPrimitive(
                  confirmSvAction.svcRules_ConfirmSvValue.newMemberParty
                )
                newMemberName = confirmSvAction.svcRules_ConfirmSvValue.newMemberName
                isSvPartOfSvc <- store
                  .getSvcRules()
                  .map(svcRules =>
                    isSvcMemberParty(newMemberParty, svcRules) || isSvcMemberName(
                      newMemberName,
                      svcRules,
                    )
                  )
              } yield isSvConfirmed || isSvPartOfSvc
            case action =>
              throw new UnsupportedOperationException(
                show"svc rules $action is not yet supported"
              )
          }
        case _ =>
          throw new UnsupportedOperationException("unsupported action")
      }
    }
  }

  override protected def isLeader(): Future[Boolean] = store.svIsLeader()
}
