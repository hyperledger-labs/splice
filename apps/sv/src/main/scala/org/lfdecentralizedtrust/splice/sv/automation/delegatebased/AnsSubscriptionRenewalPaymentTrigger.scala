// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import cats.implicits.catsSyntaxApplicativeId
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AppTransferContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.{
  AnsEntry,
  AnsEntryContext,
  AnsEntryContext_CollectEntryRenewalPayment,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.SubscriptionPayment
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

class AnsSubscriptionRenewalPaymentTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      SubscriptionPayment.ContractId,
      SubscriptionPayment,
    ](
      svTaskContext.dsoStore,
      SubscriptionPayment.COMPANION,
    )
    with SvTaskBasedTrigger[
      AssignedContract[SubscriptionPayment.ContractId, SubscriptionPayment]
    ] {

  private val dsoStore = svTaskContext.dsoStore
  private val connection = svTaskContext.connection
  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  // We do not actively reject ANS renewal payment on invalid states. Instead, we do nothing and rely on payment expiry.
  // The cases where this happens are the following:
  //  1. There is no transfer context for the given rounds, i.e., the payment is expired in some form.
  //  2a. There is no entry because the entry has already been expired, i.e., the payment was too late.
  //  2b. There is no entry for another reason, which is not expected
  override def completeTaskAsDsoDelegate(
      subscriptionPayment: AssignedContract[
        SubscriptionPayment.ContractId,
        SubscriptionPayment,
      ],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val AssignedContract(payment, _) = subscriptionPayment
    dsoStore.lookupAnsEntryContext(subscriptionPayment.contract.payload.reference).flatMap {
      case Some(ansContext) =>
        for {
          transferContextOpt <- dsoStore.getDsoTransferContextForRound(payment.payload.round)
          now = context.clock.now
          result <- transferContextOpt match {
            case Some(transferContext) =>
              dsoStore.lookupAnsEntryByName(ansContext.payload.name, now).flatMap {
                case Some(entry) =>
                  collectPayment(
                    ansContext.contract.contractId,
                    payment.contractId,
                    entry,
                    transferContext,
                    controller,
                  )
                case None =>
                  if (now.toInstant.isBefore(payment.payload.thisPaymentDueAt)) {
                    val msg =
                      s"skipping as entry doesn't exists ${ansContext.payload.name} which is not expected."
                    logger.warn(msg)
                    TaskSuccess(msg).pure[Future]
                  } else {
                    TaskSuccess(
                      s"skipping as entry ${ansContext.payload.name} has already been expired."
                    ).pure[Future]
                  }
              }
            case None =>
              TaskSuccess(
                s"skipping as round ${payment.payload.round} is no longer active."
              ).pure[Future]
          }
        } yield result
      case None =>
        throw Status.NOT_FOUND
          .withDescription(
            s"No associated ans entry context for reference ${subscriptionPayment.contract.payload.reference} was found."
          )
          .asRuntimeException()
    }
  }

  private def collectPayment(
      ansContextCId: AnsEntryContext.ContractId,
      paymentCid: SubscriptionPayment.ContractId,
      entry: AssignedContract[AnsEntry.ContractId, AnsEntry],
      transferContext: AppTransferContext,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- dsoStore.getDsoRules()
    ansRules <- dsoStore.getAnsRules()
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_CollectEntryRenewalPayment(
        ansContextCId,
        new AnsEntryContext_CollectEntryRenewalPayment(
          paymentCid,
          entry.contractId,
          transferContext,
          ansRules.contractId,
        ),
        Optional.of(controller),
      )
    )
    taskOutcome <- connection(SpliceLedgerConnectionPriority.Low)
      .submit(
        actAs = Seq(svParty),
        readAs = Seq(dsoParty),
        update = cmd,
      )
      .noDedup
      .yieldUnit()
      .map { _ =>
        TaskSuccess(s"renewed ans entry ${entry.payload.name} by collecting payment $paymentCid")
      }
  } yield taskOutcome
}
