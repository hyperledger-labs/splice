// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TaskFailed,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  ClosedMiningRound,
  SummarizingMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  Confirmation,
  DsoRules_ExecuteConfirmedAction,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_AnsEntryContext,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ansentrycontext_actionrequiringconfirmation.{
  ANSRARC_CollectInitialEntryPayment,
  ANSRARC_RejectEntryInitialPayment,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.{
  CRARC_MiningRound_Archive,
  CRARC_MiningRound_StartIssuing,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.*
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.sv.SvApp.{isDevNet, isSvName, isSvParty}
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
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
      svTaskContext.dsoStore,
      Confirmation.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[Confirmation.ContractId, Confirmation]] {

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
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
          dsoRules <- store.getDsoRules()
          requiredNumConfirmations = Thresholds.requiredNumVotes(dsoRules)
          confirmations <- store.listConfirmations(action)
          uniqueConfirmations = confirmations.distinctBy(_.payload.confirmer)
          taskOutcome <-
            if (uniqueConfirmations.size >= requiredNumConfirmations) {
              for {
                amuletRules <- store.getAmuletRules()
                amuletRulesId = amuletRules.contractId
                cmd = dsoRules.exercise(
                  _.exerciseDsoRules_ExecuteConfirmedAction(
                    new DsoRules_ExecuteConfirmedAction(
                      action,
                      java.util.Optional.of(amuletRulesId),
                      uniqueConfirmations
                        .map(_.contractId)
                        .asJava, // TODO(#3300) report duplicated and add test cases to make sure no duplicated confirmations here
                    )
                  )
                )
                res <- for {
                  outcome <- svTaskContext.connection
                    .submit(
                      Seq(store.key.svParty),
                      Seq(store.key.dsoParty),
                      cmd,
                    )
                    .noDedup
                    .yieldResult()
                } yield Some(outcome)
              } yield {
                res
                  .map(_ => {
                    TaskSuccess(
                      show"executed action $action as there are ${uniqueConfirmations.size} confirmation(s) which is >=" +
                        show" the required $requiredNumConfirmations confirmations."
                    )
                  })
                  .getOrElse(TaskFailed(show"failed to execute action $action."))
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
    // we check *OnDomain below because we expect to get retriggered with the
    // corrected domain if the contract in question is being reassigned
    confirmation.payload.action match {
      case arcAmuletRules: ARC_AmuletRules =>
        arcAmuletRules.amuletRulesAction match {
          case startIssuingAction: CRARC_MiningRound_StartIssuing =>
            val sumRoundCid =
              startIssuingAction.amuletRules_MiningRound_StartIssuingValue.miningRoundCid
            store.multiDomainAcsStore
              .lookupContractByIdOnDomain(SummarizingMiningRound.COMPANION)(
                confirmation.domain,
                sumRoundCid,
              )
              .map(_.isEmpty)
          case archiveAction: CRARC_MiningRound_Archive =>
            val closedRoundCid =
              archiveAction.amuletRules_MiningRound_ArchiveValue.closedRoundCid
            store.multiDomainAcsStore
              .lookupContractByIdOnDomain(ClosedMiningRound.COMPANION)(
                confirmation.domain,
                closedRoundCid,
              )
              .map(_.isEmpty)
          case action =>
            throw new UnsupportedOperationException(
              show"AmuletRules $action is not yet supported"
            )
        }
      case arcDsoRules: ARC_DsoRules =>
        arcDsoRules.dsoAction match {
          case confirmSvAction: SRARC_ConfirmSvOnboarding =>
            for {
              isSvOnboardingConfirmed <- store
                .lookupSvOnboardingConfirmedByParty(
                  PartyId.tryFromProtoPrimitive(
                    confirmSvAction.dsoRules_ConfirmSvOnboardingValue.newSvParty
                  )
                )
                .map(_.nonEmpty)
              newSvParty = PartyId.tryFromProtoPrimitive(
                confirmSvAction.dsoRules_ConfirmSvOnboardingValue.newSvParty
              )
              newSvName = confirmSvAction.dsoRules_ConfirmSvOnboardingValue.newSvName
              isSvPartOfDso <- store
                .getDsoRules()
                .map(dsoRules =>
                  isSvParty(newSvParty, dsoRules) || (
                    !isDevNet(dsoRules) && isSvName(newSvName, dsoRules)
                  )
                )
            } yield isSvOnboardingConfirmed || isSvPartOfDso
          case action: SRARC_CreateTransferCommandCounter =>
            for {
              transferCommandCounterO <- store.lookupTransferCommandCounterBySender(
                PartyId.tryFromProtoPrimitive(
                  action.dsoRules_CreateTransferCommandCounterValue.sender
                )
              )
            } yield transferCommandCounterO.isDefined
          case action =>
            throw new UnsupportedOperationException(
              show"DsoRules $action is not yet supported"
            )
        }
      case arcAnsEntryContext: ARC_AnsEntryContext =>
        arcAnsEntryContext.ansEntryContextAction match {
          case _: ANSRARC_CollectInitialEntryPayment =>
            store.lookupAnsEntryContext(arcAnsEntryContext.ansEntryContextCid).flatMap {
              case Some(ansContext) =>
                store
                  .lookupAnsEntryByName(ansContext.payload.name, context.clock.now)
                  .map(_.isDefined)
              case None =>
                // The ans context no longer exists, it doesn't make sense to retry collecting the payment.
                Future.successful(true)
            }
          case rejectPaymentAction: ANSRARC_RejectEntryInitialPayment =>
            store
              .lookupSubscriptionInitialPayment(
                rejectPaymentAction.ansEntryContext_RejectEntryInitialPaymentValue.paymentCid
              )
              .map(_.isEmpty)
          case action =>
            throw new UnsupportedOperationException(
              show"ANS entry context $action is not yet supported"
            )
        }
      case _ =>
        throw new UnsupportedOperationException("unsupported action")
    }
  }

}
