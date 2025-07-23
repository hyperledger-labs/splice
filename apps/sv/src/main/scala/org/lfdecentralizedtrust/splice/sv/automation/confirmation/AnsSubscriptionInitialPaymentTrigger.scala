// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AppTransferContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.{
  AnsEntryContext,
  AnsEntryContext_CollectInitialEntryPayment,
  AnsEntryContext_RejectEntryInitialPayment,
  AnsRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AnsEntryContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ansentrycontext_actionrequiringconfirmation.{
  ANSRARC_CollectInitialEntryPayment,
  ANSRARC_RejectEntryInitialPayment,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  Confirmation,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.SubscriptionInitialPayment
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.AnsUtil
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import cats.implicits.catsSyntaxApplicativeId
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

class AnsSubscriptionInitialPaymentTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    spliceInstanceNamesConfig: SpliceInstanceNamesConfig,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      SubscriptionInitialPayment.ContractId,
      SubscriptionInitialPayment,
    ](
      dsoStore,
      SubscriptionInitialPayment.COMPANION,
    ) {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  override def completeTask(
      subscriptionInitialPayment: AssignedContract[
        SubscriptionInitialPayment.ContractId,
        SubscriptionInitialPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val AssignedContract(payment, _) = subscriptionInitialPayment
    dsoStore.lookupAnsEntryContext(subscriptionInitialPayment.contract.payload.reference).flatMap {
      case Some(ansContext) =>
        val entryName = ansContext.payload.name
        for {
          transferContextOpt <- dsoStore.getDsoTransferContextForRound(payment.payload.round)
          result <- transferContextOpt match {
            case Some(transferContext) =>
              def confirmToReject(reason: String) = confirmRejectPayment(
                ansContext.contract.contractId,
                payment.contractId,
                entryName,
                reason,
                transferContext,
              )
              val entryUrl = ansContext.payload.url
              val entryDescription = ansContext.payload.description
              val ansUtil =
                new AnsUtil(spliceInstanceNamesConfig.nameServiceNameAcronym.toLowerCase())

              if (!ansUtil.isValidEntryName(entryName)) {
                confirmToReject(s"entry name ($entryName) is not valid")
              } else if (!ansUtil.isValidEntryUrl(entryUrl)) {
                confirmToReject(s"entry url ($entryUrl) is not valid")
              } else if (!ansUtil.isValidEntryDescription(entryDescription)) {
                confirmToReject(s"entry description ($entryDescription) is not valid")
              } else {
                // check if the entry already exists
                dsoStore
                  .lookupAnsEntryByNameWithOffset(entryName, context.clock.now)
                  .flatMap {
                    case QueryResult(_, Some(entry)) =>
                      confirmToReject(
                        s"entry already exists and owned by ${entry.payload.user}."
                      )
                    case QueryResult(offset, None) => {
                      // check if a confirmation by us for this entry already exists
                      getConflictingInitialPaymentConfirmation(entryName, payment.contractId)
                        .flatMap {
                          case Some(c) =>
                            confirmToReject(
                              s"other initial payment collection has been confirmed for the same ans name ($entryName) with confirmation ${c}."
                            )
                          case None =>
                            confirmCollectPayment(
                              ansContext.contract.contractId,
                              payment.contractId,
                              entryName,
                              transferContext,
                              offset,
                            )
                        }
                    }
                  }
              }
            case None =>
              dsoStore
                .getDsoTransferContext()
                .flatMap(
                  confirmRejectPayment(
                    ansContext.contract.contractId,
                    payment.contractId,
                    entryName,
                    s"round ${payment.payload.round} is no longer active.",
                    _,
                  )
                )
          }
        } yield result

      case None =>
        throw Status.INTERNAL
          .withDescription(
            s"No ans entry context for reference ${subscriptionInitialPayment.contract.payload.reference} was found."
          )
          .asRuntimeException()
    }
  }

  private def getConflictingInitialPaymentConfirmation(
      entryName: String,
      paymentId: SubscriptionInitialPayment.ContractId,
  )(implicit
      tc: TraceContext
  ): Future[Option[Confirmation.ContractId]] =
    dsoStore
      .listInitialPaymentConfirmationByAnsName(svParty, entryName)
      .map { confirmations =>
        confirmations
          .find { c =>
            c.payload.action match {
              case arcAnsEntryContext: ARC_AnsEntryContext =>
                arcAnsEntryContext.ansEntryContextAction match {
                  case a: ANSRARC_CollectInitialEntryPayment =>
                    a.ansEntryContext_CollectInitialEntryPaymentValue.paymentCid != paymentId
                  case _ =>
                    false
                }
              case _ => false
            }
          }
          .map { _.contractId }
      }

  private def ansCollectInitialEntryPaymentAction(
      paymentId: SubscriptionInitialPayment.ContractId,
      transferContext: AppTransferContext,
      ansRulesCid: AnsRules.ContractId,
      ansEntryContextCid: AnsEntryContext.ContractId,
  ): ActionRequiringConfirmation = new ARC_AnsEntryContext(
    ansEntryContextCid,
    new ANSRARC_CollectInitialEntryPayment(
      new AnsEntryContext_CollectInitialEntryPayment(
        paymentId,
        transferContext,
        ansRulesCid,
      )
    ),
  )

  private def ansRejectEntryInitialPaymentAction(
      paymentId: SubscriptionInitialPayment.ContractId,
      transferContext: AppTransferContext,
      ansRulesCid: AnsRules.ContractId,
      ansEntryContextCid: AnsEntryContext.ContractId,
  ): ActionRequiringConfirmation = new ARC_AnsEntryContext(
    ansEntryContextCid,
    new ANSRARC_RejectEntryInitialPayment(
      new AnsEntryContext_RejectEntryInitialPayment(
        paymentId,
        transferContext,
        ansRulesCid,
      )
    ),
  )

  private def confirmCollectPayment(
      ansContextCId: AnsEntryContext.ContractId,
      paymentCid: SubscriptionInitialPayment.ContractId,
      entryName: String,
      transferContext: AppTransferContext,
      ansEntryNameOffset: Long,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- dsoStore.getDsoRules()
    ansRules <- dsoStore.getAnsRules()
    action = ansCollectInitialEntryPaymentAction(
      paymentCid,
      transferContext,
      ansRules.contractId,
      ansContextCId,
    )
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_ConfirmAction(
        svParty.toProtoPrimitive,
        action,
      )
    )
    taskOutcome <- connection
      .submit(
        actAs = Seq(svParty),
        readAs = Seq(dsoParty),
        update = cmd,
      )
      .withDedup(
        commandId = SpliceLedgerConnection.CommandId(
          "org.lfdecentralizedtrust.splice.sv.createAnsCollectInitialEntryPaymentConfirmation",
          Seq(svParty, dsoParty),
          entryName,
        ),
        // we can safely assume that `ansEntryNameOffset` is smaller than the offset from the ansInitialPaymentConfirmation
        deduplicationOffset = ansEntryNameOffset,
      )
      .yieldUnit()
      .map { _ =>
        TaskSuccess(
          s"confirmed to create ans entry $entryName by collecting payment $paymentCid"
        )
      }
  } yield taskOutcome

  private def confirmRejectPayment(
      ansContextCId: AnsEntryContext.ContractId,
      paymentCid: SubscriptionInitialPayment.ContractId,
      entryName: String,
      reason: String,
      transferContext: AppTransferContext,
  )(implicit tc: TraceContext) = for {
    dsoRules <- dsoStore.getDsoRules()
    ansRules <- dsoStore.getAnsRules()
    action = ansRejectEntryInitialPaymentAction(
      paymentCid,
      transferContext,
      ansRules.contractId,
      ansContextCId,
    )

    // look up the rejection confirmation for this payment
    queryResult <- dsoStore.lookupAnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(
      svParty,
      paymentCid,
    )
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_ConfirmAction(
        svParty.toProtoPrimitive,
        action,
      )
    )
    taskOutcome <- queryResult match {
      case QueryResult(offset, None) =>
        connection
          .submit(
            actAs = Seq(svParty),
            readAs = Seq(dsoParty),
            update = cmd,
          )
          .withDedup(
            commandId = SpliceLedgerConnection.CommandId(
              "org.lfdecentralizedtrust.splice.sv.createAnsRejectInitialEntryPaymentConfirmation",
              Seq(svParty, dsoParty),
              paymentCid.contractId,
            ),
            deduplicationOffset = offset,
          )
          .yieldUnit()
          .map { _ =>
            val msg = s"confirmed to reject payment $paymentCid for ans entry $entryName: $reason"
            TaskSuccess(msg)
          }
      case QueryResult(_, Some(_)) =>
        TaskSuccess(
          s"skipping as confirmation of payment rejection from $svParty is already created for this payment $paymentCid"
        ).pure[Future]

    }
  } yield taskOutcome
}
