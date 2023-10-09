package com.daml.network.sv.automation.confirmation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.coin.AppTransferContext
import com.daml.network.codegen.java.cn.cns.{
  CnsEntryContext,
  CnsEntryContext_CollectInitialEntryPayment,
  CnsEntryContext_RejectEntryInitialPayment,
  CnsRules,
}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CnsEntryContext
import com.daml.network.codegen.java.cn.svcrules.cnsentrycontext_actionrequiringconfirmation.{
  CNSRARC_CollectInitialEntryPayment,
  CNSRARC_RejectEntryInitialPayment,
}
import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionInitialPayment
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.CnsUtil
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import cats.implicits.catsSyntaxApplicativeId

import scala.concurrent.{ExecutionContext, Future}

class CnsSubscriptionInitialPaymentTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      SubscriptionInitialPayment.ContractId,
      SubscriptionInitialPayment,
    ](
      svcStore,
      SubscriptionInitialPayment.COMPANION,
    ) {

  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  override def completeTask(
      subscriptionInitialPayment: AssignedContract[
        SubscriptionInitialPayment.ContractId,
        SubscriptionInitialPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val AssignedContract(payment, _) = subscriptionInitialPayment
    svcStore.lookupCnsEntryContext(subscriptionInitialPayment.contract.payload.reference).flatMap {
      case Some(cnsContext) =>
        val entryName = cnsContext.payload.name
        for {
          transferContextOpt <- svcStore.getSvcTransferContextForRound(payment.payload.round)
          result <- transferContextOpt match {
            case Some(transferContext) =>
              def confirmToReject(reason: String) = confirmRejectPayment(
                cnsContext.contract.contractId,
                payment.contractId,
                entryName,
                reason,
                transferContext,
              )
              val entryUrl = cnsContext.payload.url
              val entryDescription = cnsContext.payload.description

              if (!CnsUtil.isValidEntryName(entryName)) {
                confirmToReject(s"entry name ($entryName) is not valid")
              } else if (!CnsUtil.isValidEntryUrl(entryUrl)) {
                confirmToReject(s"entry url ($entryUrl) is not valid")
              } else if (!CnsUtil.isValidEntryDescription(entryDescription)) {
                confirmToReject(s"entry description ($entryDescription) is not valid")
              } else {
                svcStore
                  .listInitialPaymentConfirmationByCnsName(
                    svParty,
                    entryName,
                  )
                  .flatMap { confirmations =>
                    val otherPaymentAcceptedConfirmations = confirmations.filter { c =>
                      c.payload.action match {
                        case arcCnsEntryContext: ARC_CnsEntryContext =>
                          arcCnsEntryContext.cnsEntryContextAction match {
                            case a: CNSRARC_CollectInitialEntryPayment =>
                              a.cnsEntryContext_CollectInitialEntryPaymentValue.paymentCid != payment.contractId
                            case _ =>
                              false
                          }
                        case _ => false
                      }
                    }
                    // if there are existing accepted confirmation of other payment and with the same cns entry name, we will reject this payment.
                    if (otherPaymentAcceptedConfirmations.isEmpty)
                      svcStore.lookupCnsEntryByName(entryName).flatMap {
                        case None =>
                          // confirm to collect the payment and create the entry
                          confirmCollectPayment(
                            cnsContext.contract.contractId,
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
                        s"other initial payment collection has been confirmed for the same cns name ($entryName) with confirmation ${otherPaymentAcceptedConfirmations
                            .map(_.contractId)}"
                      )
                    }
                  }
              }
            case None =>
              svcStore
                .getSvcTransferContext()
                .flatMap(
                  confirmRejectPayment(
                    cnsContext.contract.contractId,
                    payment.contractId,
                    entryName,
                    s"round ${payment.payload.round} is no longer active.",
                    _,
                  )
                )
          }
        } yield result

      case None =>
        TaskSuccess(
          s"skipping as no cns entry context for reference ${subscriptionInitialPayment.contract.payload.reference} was found."
        ).pure[Future]
    }
  }

  private def cnsCollectInitialEntryPaymentAction(
      paymentId: SubscriptionInitialPayment.ContractId,
      transferContext: AppTransferContext,
      cnsRulesCid: CnsRules.ContractId,
      cnsEntryContextCid: CnsEntryContext.ContractId,
  ): ActionRequiringConfirmation = new ARC_CnsEntryContext(
    cnsEntryContextCid,
    new CNSRARC_CollectInitialEntryPayment(
      new CnsEntryContext_CollectInitialEntryPayment(
        paymentId,
        transferContext,
        cnsRulesCid,
      )
    ),
  )

  private def cnsRejectEntryInitialPaymentAction(
      paymentId: SubscriptionInitialPayment.ContractId,
      transferContext: AppTransferContext,
      cnsRulesCid: CnsRules.ContractId,
      cnsEntryContextCid: CnsEntryContext.ContractId,
  ): ActionRequiringConfirmation = new ARC_CnsEntryContext(
    cnsEntryContextCid,
    new CNSRARC_RejectEntryInitialPayment(
      new CnsEntryContext_RejectEntryInitialPayment(
        paymentId,
        transferContext,
        cnsRulesCid,
      )
    ),
  )

  private def confirmCollectPayment(
      cnsContextCId: CnsEntryContext.ContractId,
      paymentCid: SubscriptionInitialPayment.ContractId,
      entryName: String,
      transferContext: AppTransferContext,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    svcRules <- svcStore.getSvcRules()
    cnsRules <- svcStore.getCnsRules()
    action = cnsCollectInitialEntryPaymentAction(
      paymentCid,
      transferContext,
      cnsRules.contractId,
      cnsContextCId,
    )
    // look up the confirmation for this payment created by this SV no matter if it is a acceptance or rejection
    queryResult <- svcStore.lookupCnsInitialPaymentConfirmationByPaymentIdWithOffset(
      svParty,
      paymentCid,
    )
    cmd = svcRules.exercise(
      _.exerciseSvcRules_ConfirmAction(
        svParty.toProtoPrimitive,
        action,
      )
    )
    taskOutcome <- queryResult match {
      case QueryResult(offset, None) =>
        connection
          .submit(
            actAs = Seq(svParty),
            readAs = Seq(svcParty),
            update = cmd,
          )
          .withDedup(
            commandId = CNLedgerConnection.CommandId(
              "com.daml.network.sv.createCnsCollectInitialEntryPaymentConfirmation",
              Seq(svParty, svcParty),
              paymentCid.contractId,
            ),
            deduplicationOffset = offset,
          )
          .yieldUnit()
          .map { _ =>
            TaskSuccess(
              s"confirmed to create cns entry $entryName by collecting payment $paymentCid"
            )
          }
      case QueryResult(_, Some(_)) =>
        TaskSuccess(
          s"skipping as confirmation (either acceptance or rejection) from $svParty is already created for this payment $paymentCid"
        ).pure[Future]

    }
  } yield taskOutcome

  private def confirmRejectPayment(
      cnsContextCId: CnsEntryContext.ContractId,
      paymentCid: SubscriptionInitialPayment.ContractId,
      entryName: String,
      reason: String,
      transferContext: AppTransferContext,
  )(implicit tc: TraceContext) = for {
    svcRules <- svcStore.getSvcRules()
    cnsRules <- svcStore.getCnsRules()
    action = cnsRejectEntryInitialPaymentAction(
      paymentCid,
      transferContext,
      cnsRules.contractId,
      cnsContextCId,
    )

    // look up the rejection confirmation for this payment created by this.
    queryResult <- svcStore.lookupCnsRejectedInitialPaymentConfirmationByPaymentIdWithOffset(
      svParty,
      paymentCid,
    )
    cmd = svcRules.exercise(
      _.exerciseSvcRules_ConfirmAction(
        svParty.toProtoPrimitive,
        action,
      )
    )
    taskOutcome <- queryResult match {
      case QueryResult(offset, None) =>
        connection
          .submit(
            actAs = Seq(svParty),
            readAs = Seq(svcParty),
            update = cmd,
          )
          .withDedup(
            commandId = CNLedgerConnection.CommandId(
              "com.daml.network.sv.createCnsRejectInitialEntryPaymentConfirmation",
              Seq(svParty, svcParty),
              paymentCid.contractId,
            ),
            deduplicationOffset = offset,
          )
          .yieldUnit()
          .map { _ =>
            val msg = s"confirmed to reject payment $paymentCid for cns entry $entryName: $reason"
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
