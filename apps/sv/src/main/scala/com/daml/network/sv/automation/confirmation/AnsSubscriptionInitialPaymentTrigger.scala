// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.amuletrules.AppTransferContext
import com.daml.network.codegen.java.splice.ans.{
  AnsEntryContext,
  AnsEntryContext_CollectInitialEntryPayment,
  AnsEntryContext_RejectEntryInitialPayment,
  AnsRules,
}
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AnsEntryContext
import com.daml.network.codegen.java.splice.dsorules.ansentrycontext_actionrequiringconfirmation.{
  ANSRARC_CollectInitialEntryPayment,
  ANSRARC_RejectEntryInitialPayment,
}
import com.daml.network.codegen.java.splice.dsorules.ActionRequiringConfirmation
import com.daml.network.codegen.java.splice.wallet.subscriptions.SubscriptionInitialPayment
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.sv.util.AnsUtil
import com.daml.network.util.AssignedContract
import com.daml.network.config.SpliceInstanceNamesConfig
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
                dsoStore
                  .listInitialPaymentConfirmationByAnsName(
                    svParty,
                    entryName,
                  )
                  .flatMap { confirmations =>
                    val otherPaymentAcceptedConfirmations = confirmations.filter { c =>
                      c.payload.action match {
                        case arcAnsEntryContext: ARC_AnsEntryContext =>
                          arcAnsEntryContext.ansEntryContextAction match {
                            case a: ANSRARC_CollectInitialEntryPayment =>
                              a.ansEntryContext_CollectInitialEntryPaymentValue.paymentCid != payment.contractId
                            case _ =>
                              false
                          }
                        case _ => false
                      }
                    }
                    // if there are existing accepted confirmation of other payment and with the same ans entry name, we will reject this payment.
                    if (otherPaymentAcceptedConfirmations.isEmpty)
                      dsoStore.lookupAnsEntryByName(entryName, context.clock.now).flatMap {
                        case None =>
                          // confirm to collect the payment and create the entry
                          confirmCollectPayment(
                            ansContext.contract.contractId,
                            payment.contractId,
                            entryName,
                            transferContext,
                          )
                        case Some(entry) =>
                          confirmToReject(
                            s"entry already exists and owned by ${entry.payload.user}."
                          )
                      }
                    else {
                      confirmToReject(
                        s"other initial payment collection has been confirmed for the same ans name ($entryName) with confirmation ${otherPaymentAcceptedConfirmations
                            .map(_.contractId)}"
                      )
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
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- dsoStore.getDsoRules()
    ansRules <- dsoStore.getAnsRules()
    action = ansCollectInitialEntryPaymentAction(
      paymentCid,
      transferContext,
      ansRules.contractId,
      ansContextCId,
    )
    // look up the confirmation for this payment created by this SV no matter if it is a acceptance or rejection
    queryResult <- dsoStore.lookupAnsInitialPaymentConfirmationByPaymentIdWithOffset(
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
              "com.daml.network.sv.createAnsCollectInitialEntryPaymentConfirmation",
              Seq(svParty, dsoParty),
              paymentCid.contractId,
            ),
            deduplicationOffset = offset,
          )
          .yieldUnit()
          .map { _ =>
            TaskSuccess(
              s"confirmed to create ans entry $entryName by collecting payment $paymentCid"
            )
          }
      case QueryResult(_, Some(_)) =>
        TaskSuccess(
          s"skipping as confirmation (either acceptance or rejection) from $svParty is already created for this payment $paymentCid"
        ).pure[Future]

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
              "com.daml.network.sv.createAnsRejectInitialEntryPaymentConfirmation",
              Seq(svParty, dsoParty),
              paymentCid.contractId,
            ),
            deduplicationOffset = offset,
          )
          .yieldUnit()
          .map { _ =>
            val msg = s"confirmed to reject payment $paymentCid for ans entry $entryName: $reason"
            logger.warn(msg)
            TaskSuccess(msg)
          }
      case QueryResult(_, Some(_)) =>
        TaskSuccess(
          s"skipping as confirmation of payment rejection from $svParty is already created for this payment $paymentCid"
        ).pure[Future]

    }
  } yield taskOutcome
}
