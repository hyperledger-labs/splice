// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskFailed,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  ClosedMiningRound,
  OpenMiningRound,
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
import com.digitalasset.canton.util.StampedLockWithHandle
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
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

  // Lock to avoid contention as any confirmation after the threshold will trigger a conflicting submission.
  // TODO(tech-debt) Replace single lock by one per action
  private val lock = new StampedLockWithHandle()

  private val RecheckStalenessDuration = java.time.Duration.ofMillis(200)

  private val store = svTaskContext.dsoStore

  override def completeTaskAsDsoDelegate(
      confirmationContract: AssignedContract[Confirmation.ContractId, Confirmation],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val action = confirmationContract.payload.action
    isStaleAction(confirmationContract).flatMap { isStale =>
      if (isStale)
        Future.successful(
          TaskSuccess(
            show"Skipping as the confirmed $action has already been executed"
          )
        )
      else {
        val beforeLock = context.pollingClock.now
        lock.withWriteLock {
          val afterLock = context.pollingClock.now
          val waitedFor = afterLock - beforeLock
          logger.debug(s"Acquired lock after $waitedFor")
          // Recheck for staleness if we waited more than RecheckStalenessDuration as another task may have completed it until we acquired the lock
          (if (waitedFor.compareTo(RecheckStalenessDuration) < 0) Future.successful(false)
           else isStaleAction(confirmationContract)).flatMap { isStale =>
            if (isStale)
              Future.successful(
                TaskSuccess(
                  show"Skipping as the confirmed $action has already been executed"
                )
              )
            else
              for {
                dsoRules <- store.getDsoRules()
                svs = dsoRules.payload.svs.keySet.asScala
                requiredNumConfirmations = Thresholds.requiredNumVotes(dsoRules)
                allConfirmations <- store.listConfirmations(action)
                now = context.clock.now
                (unexpiredConfirmations, expiredConfirmations) = allConfirmations.partition(c =>
                  now.toInstant.isBefore(c.payload.expiresAt)
                )
                _ = if (expiredConfirmations.nonEmpty) {
                  logger.info(
                    show"Ignoring expired confirmations from ${expiredConfirmations.map(_.payload.confirmer)}"
                  )
                }

                (confirmationsOfMembers, confirmationsOfNonMembers) = unexpiredConfirmations
                  .partition(c => svs.contains(c.payload.confirmer))
                _ = if (confirmationsOfNonMembers.nonEmpty) {
                  logger.info(
                    show"Ignoring confirmations from ${confirmationsOfNonMembers.map(_.payload.confirmer)} as they are no longer an SV"
                  )
                }
                uniqueConfirmations = confirmationsOfMembers.distinctBy(_.payload.confirmer)
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
                              .asJava, // TODO(DACH-NY/canton-network-node##3300) report duplicated and add test cases to make sure no duplicated confirmations here
                            Optional.of(controller),
                          )
                        )
                      )
                      res <- for {
                        outcome <- svTaskContext
                          .connection(SpliceLedgerConnectionPriority.High)
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
      }
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
          case _: SRARC_CreateExternalPartyAmuletRules =>
            for {
              rulesO <- store.lookupExternalPartyAmuletRules()
            } yield rulesO.value.isDefined
          case action =>
            throw new UnsupportedOperationException(
              show"DsoRules $action is not yet supported"
            )
        }
      case arcAnsEntryContext: ARC_AnsEntryContext =>
        arcAnsEntryContext.ansEntryContextAction match {
          case collectPaymentAction: ANSRARC_CollectInitialEntryPayment =>
            for {
              isRoundFromTransferContextClosed <- store.multiDomainAcsStore
                .lookupContractById(OpenMiningRound.COMPANION)(
                  collectPaymentAction.ansEntryContext_CollectInitialEntryPaymentValue.transferContext.openMiningRound
                )
                .map(_.isEmpty)
              isAnsContextDefined <- store
                .lookupAnsEntryContext(arcAnsEntryContext.ansEntryContextCid)
                .flatMap {
                  case Some(ansContext) =>
                    store
                      .lookupAnsEntryByName(ansContext.payload.name, context.clock.now)
                      .map(_.isDefined)
                  case None =>
                    // The ans context no longer exists, it doesn't make sense to retry collecting the payment.
                    Future.successful(true)
                }
            } yield isAnsContextDefined || isRoundFromTransferContextClosed
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
