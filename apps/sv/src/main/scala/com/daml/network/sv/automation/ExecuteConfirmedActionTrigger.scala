package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
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
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.util.SvUtil
import com.daml.network.sv.SvApp.{isSvcMemberName, isSvcMemberParty}
import io.opentelemetry.api.trace.Tracer
import com.daml.network.util.Contract
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
) extends OnReadyContractTrigger.Template[Confirmation.ContractId, Confirmation](
      store,
      Confirmation.COMPANION,
    )
    with SvTaskBasedTrigger[ReadyContract[Confirmation.ContractId, Confirmation]] {

  override def completeTaskAsLeader(
      confirmationContract: ReadyContract[Confirmation.ContractId, Confirmation]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val action = confirmationContract.contract.payload.action
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
      confirmationContract: ReadyContract[Confirmation.ContractId, Confirmation]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(
        show"ignoring ${PrettyContractId(confirmationContract.contract)}, as we're not the leader"
      )
    )
  }

  private def isStaleAction(
      confirmation: ReadyContract[Confirmation.ContractId, Confirmation]
  ): Future[Boolean] = {
    // Add new cases as we port more triggers which require confirmation
    confirmation.contract.payload.action match {
      case arcCoinRules: ARC_CoinRules =>
        arcCoinRules.coinRulesAction match {
          case startIssuingAction: CRARC_MiningRound_StartIssuing =>
            val sumRoundCid =
              startIssuingAction.coinRules_MiningRound_StartIssuingValue.miningRoundCid
            store.multiDomainAcsStore
              .lookupContractByIdOnDomain(SummarizingMiningRound.COMPANION)(
                confirmation.domain,
                sumRoundCid,
              )
              .map(_.isEmpty)
          case archiveAction: CRARC_MiningRound_Archive =>
            val closedRoundCid =
              archiveAction.coinRules_MiningRound_ArchiveValue.closedRoundCid
            store.multiDomainAcsStore
              .lookupContractByIdOnDomain(ClosedMiningRound.COMPANION)(
                confirmation.domain,
                closedRoundCid,
              )
              .map(_.isEmpty)
          case action =>
            throw new UnsupportedOperationException(
              show"coin rules $action is not yet supported"
            )
        }
      case arcSvcRules: ARC_SvcRules =>
        arcSvcRules.svcAction match {
          case confirmSvAction: SRARC_ConfirmSv =>
            for {
              isSvConfirmed <- store.multiDomainAcsStore
                .findContractOnDomainWithOffset(SvConfirmed.COMPANION)(
                  confirmation.domain,
                  (c: Contract[SvConfirmed.ContractId, SvConfirmed]) =>
                    c.payload.svc == store.key.svcParty.toProtoPrimitive &&
                      c.payload.svParty == confirmSvAction.svcRules_ConfirmSvValue.newMemberParty,
                )
                .map(_.value.nonEmpty)
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

  override protected def isLeader(): Future[Boolean] = store.svIsLeader()
}
