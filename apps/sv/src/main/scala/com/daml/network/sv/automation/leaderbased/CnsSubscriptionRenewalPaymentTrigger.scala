package com.daml.network.sv.automation.leaderbased

import akka.stream.Materializer
import cats.implicits.catsSyntaxApplicativeId
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.api.v1.coin.AppTransferContext
import com.daml.network.codegen.java.cn.cns.{
  CnsEntry,
  CnsEntryContext,
  CnsEntryContext_CollectEntryRenewalPayment,
}
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionPayment
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class CnsSubscriptionRenewalPaymentTrigger(
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
      svTaskContext.svcStore,
      SubscriptionPayment.COMPANION,
    )
    with SvTaskBasedTrigger[
      AssignedContract[SubscriptionPayment.ContractId, SubscriptionPayment]
    ] {

  private val svcStore = svTaskContext.svcStore
  private val connection = svTaskContext.connection
  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  // We do not actively reject CNS renewal payment on invalid states. Instead, we do nothing and rely on payment expiry.
  // The cases where this happens are the following:
  //  1. There is no transfer context for the given rounds, i.e., the payment is expired in some form.
  //  2a. There is no entry because the entry has already been expired, i.e., the payment was too late.
  //  2b. There is no entry for another reason, which is not expected
  override def completeTaskAsLeader(
      subscriptionPayment: AssignedContract[
        SubscriptionPayment.ContractId,
        SubscriptionPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val AssignedContract(payment, _) = subscriptionPayment
    val contextId = CnsEntryContext.ContractId.unsafeFromInterface(
      payment.payload.subscriptionData.context
    )
    svcStore.lookupCnsEntryContext(contextId).flatMap {
      case Some(cnsContext) =>
        for {
          transferContextOpt <- svcStore.getSvcTransferContextForRound(payment.payload.round)
          result <- transferContextOpt match {
            case Some(transferContext) =>
              svcStore.lookupCnsEntryByName(cnsContext.payload.name).flatMap {
                case Some(entry) =>
                  collectPayment(
                    contextId,
                    payment.contractId,
                    entry,
                    transferContext,
                  )
                case None =>
                  if (context.clock.now.toInstant.isBefore(payment.payload.thisPaymentDueAt)) {
                    val msg =
                      s"skipping as entry doesn't exists ${cnsContext.payload.name} which is not expected."
                    logger.warn(msg)
                    TaskSuccess(msg).pure[Future]
                  } else {
                    TaskSuccess(
                      s"skipping as entry ${cnsContext.payload.name} has already been expired."
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
        TaskSuccess(
          s"skipping as associated cns entry context not found: $contextId."
        ).pure[Future]
    }
  }

  private def collectPayment(
      cnsContextCId: CnsEntryContext.ContractId,
      paymentCid: SubscriptionPayment.ContractId,
      entry: AssignedContract[CnsEntry.ContractId, CnsEntry],
      transferContext: AppTransferContext,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    svcRules <- svcStore.getSvcRules()
    cnsRules <- svcStore.getCnsRules()
    cmd = svcRules.exercise(
      _.exerciseSvcRules_CollectEntryRenewalPayment(
        cnsContextCId,
        new CnsEntryContext_CollectEntryRenewalPayment(
          paymentCid,
          entry.contractId,
          transferContext,
          cnsRules.contractId,
        ),
      )
    )
    taskOutcome <- connection
      .submit(
        actAs = Seq(svParty),
        readAs = Seq(svcParty),
        update = cmd,
      )
      .noDedup
      .yieldUnit()
      .map { _ =>
        TaskSuccess(s"renewed cns entry ${entry.payload.name} by collecting payment $paymentCid")
      }
  } yield taskOutcome
}
