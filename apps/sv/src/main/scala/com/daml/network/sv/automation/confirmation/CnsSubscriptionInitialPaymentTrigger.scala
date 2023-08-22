package com.daml.network.sv.automation.confirmation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.api.v1.coin.AppTransferContext
import com.daml.network.codegen.java.cn.cns.{
  CnsEntryContext,
  CnsEntryContext_CollectInitialEntryPayment,
  CnsRules,
}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CnsEntryContext
import com.daml.network.codegen.java.cn.svcrules.cnsentrycontext_actionrequiringconfirmation.CNSRARC_CollectInitialEntryPayment
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
    val contextId = CnsEntryContext.ContractId.unsafeFromInterface(
      payment.payload.subscriptionData.context
    )
    svcStore.lookupCnsEntryContext(contextId).flatMap {
      case Some(cnsContext) =>
        if (CnsUtil.isValidCnsContent(cnsContext)) {
          for {
            transferContextOpt <- svcStore.getSvcTransferContextForRound(payment.payload.round)
            result <- transferContextOpt match {
              case Some(transferContext) =>
                svcStore
                  .listInitialPaymentConfirmationByCnsName(svParty, cnsContext.payload.name)
                  .flatMap { confirmations =>
                    if (confirmations.isEmpty)
                      svcStore.lookupCnsEntryByName(cnsContext.payload.name).flatMap {
                        case None =>
                          // confirm to collect the payment and create the entry
                          confirmCollectPayment(
                            contextId,
                            payment.contractId,
                            cnsContext.payload.name,
                            transferContext,
                          )
                        case Some(entry) =>
                          val msg =
                            s"skipping as entry already exists and owned by ${entry.payload.user}."
                          logger.warn(msg)
                          TaskSuccess(msg).pure[Future]
                      }
                    else {
                      val msg =
                        s"skipping as initial payment collection has been confirmed for this cns name: ${confirmations
                            .map(_.contractId)}"
                      logger.warn(msg)
                      TaskSuccess(msg).pure[Future]
                    }
                  }
              case None =>
                TaskSuccess(
                  s"skipping as round ${payment.payload.round} is no longer active."
                ).pure[Future]

            }
          } yield result
        } else {
          TaskSuccess(
            s"skipping as invalid cns request $cnsContext."
          ).pure[Future]
        }
      case None =>
        TaskSuccess(
          s"skipping as associated cns entry context not found: $contextId."
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
    queryResult <- svcStore.lookupCnsInitialPaymentConfirmationByCnsNameWithOffset(
      svParty,
      entryName,
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
              entryName,
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
          s"skipping as confirmation from $svParty is already created for such entry name"
        ).pure[Future]

    }
  } yield taskOutcome
}
