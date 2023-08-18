package com.daml.network.sv.automation.leaderbased

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.round.{ClosedMiningRound, SummarizingMiningRound}
import com.daml.network.codegen.java.cn.svcrules.Confirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CnsEntryContext,
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.cnsentrycontext_actionrequiringconfirmation.CNSRARC_CollectInitialEntryPayment
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.{
  CRARC_MiningRound_Archive,
  CRARC_MiningRound_StartIssuing,
}
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.sv.SvApp.{isDevNet, isSvcMemberName, isSvcMemberParty}
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.AssignedContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExecuteConfirmedActionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[Confirmation.ContractId, Confirmation](
      svTaskContext.svcStore,
      Confirmation.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[Confirmation.ContractId, Confirmation]] {

  private val store = svTaskContext.svcStore

  override def completeTaskAsLeader(
      confirmationContract: AssignedContract[Confirmation.ContractId, Confirmation]
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
          domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
          svcRules <- store.getSvcRules()
          requiredNumConfirmations = SvUtil.requiredNumVotes(svcRules)
          confirmations <- store.listConfirmations(action)
          uniqueConfirmations = confirmations.distinctBy(_.payload.confirmer)
          taskOutcome <-
            if (uniqueConfirmations.size >= requiredNumConfirmations) {
              store.getCoinRules().flatMap { coinRules =>
                val coinRulesId = coinRules.contractId
                val cmd = svcRules.contractId.exerciseSvcRules_ExecuteConfirmedAction(
                  action,
                  java.util.Optional.of(coinRulesId),
                  uniqueConfirmations
                    .map(_.contractId)
                    .asJava, // TODO(#3300) report duplicated and add test cases to make sure no duplicated confirmations here
                )
                svTaskContext.connection
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
              }
            } else {
              Future.successful(
                TaskSuccess(
                  show"not yet executing $action," +
                    show" as there are only ${uniqueConfirmations.size} out of" +
                    show" the required $requiredNumConfirmations confirmations."
                )
              )
            }
        } yield taskOutcome
    }
  }

  private def isStaleAction(
      confirmation: AssignedContract[Confirmation.ContractId, Confirmation]
  )(implicit tc: TraceContext): Future[Boolean] = {
    // Add new cases as we port more triggers which require confirmation
    confirmation.payload.action match {
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
          case confirmSvAction: SRARC_ConfirmSvOnboarding =>
            for {
              isSvOnboardingConfirmed <- store
                .lookupSvOnboardingConfirmedByPartyOnDomain(
                  PartyId.tryFromProtoPrimitive(
                    confirmSvAction.svcRules_ConfirmSvOnboardingValue.newMemberParty
                  ),
                  confirmation.domain,
                )
                .map(_.value.nonEmpty)
              newMemberParty = PartyId.tryFromProtoPrimitive(
                confirmSvAction.svcRules_ConfirmSvOnboardingValue.newMemberParty
              )
              newMemberName = confirmSvAction.svcRules_ConfirmSvOnboardingValue.newMemberName
              isSvPartOfSvc <- store
                .getSvcRules()
                .map(svcRules =>
                  isSvcMemberParty(newMemberParty, svcRules) || (
                    !isDevNet(svcRules) && isSvcMemberName(newMemberName, svcRules)
                  )
                )
            } yield isSvOnboardingConfirmed || isSvPartOfSvc
          case action =>
            throw new UnsupportedOperationException(
              show"svc rules $action is not yet supported"
            )
        }
      case arcCnsEntryContext: ARC_CnsEntryContext =>
        arcCnsEntryContext.cnsEntryContextAction match {
          case _: CNSRARC_CollectInitialEntryPayment =>
            store.lookupCnsEntryContext(arcCnsEntryContext.cnsEntryContextCid).flatMap {
              case Some(context) =>
                store.lookupCnsEntryByName(context.payload.name).map(_.isDefined)
              case None =>
                // The cns context no longer exists, it doesn't make sense to retry collecting the payment.
                Future.successful(true)
            }
          case action =>
            throw new UnsupportedOperationException(
              show"cns entry context $action is not yet supported"
            )
        }
      case _ =>
        throw new UnsupportedOperationException("unsupported action")
    }
  }

}
