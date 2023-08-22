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
import com.daml.network.codegen.java.cn.cns.CnsEntryContext
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionInitialPayment
import com.daml.network.sv.util.CnsUtil
import com.daml.network.util.{AssignedContract, Contract}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class CnsRejectSubscriptionInitialPaymentTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      SubscriptionInitialPayment.ContractId,
      SubscriptionInitialPayment,
    ](
      svTaskContext.svcStore,
      SubscriptionInitialPayment.COMPANION,
    )
    with SvTaskBasedTrigger[
      AssignedContract[SubscriptionInitialPayment.ContractId, SubscriptionInitialPayment]
    ] {

  private val svcStore = svTaskContext.svcStore
  private val connection = svTaskContext.connection
  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  override def completeTaskAsLeader(
      subscriptionInitialPayment: AssignedContract[
        SubscriptionInitialPayment.ContractId,
        SubscriptionInitialPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val AssignedContract(payment, domainId) = subscriptionInitialPayment
    val contextId = CnsEntryContext.ContractId.unsafeFromInterface(
      payment.payload.subscriptionData.context
    )
    svcStore.lookupCnsEntryContext(contextId).flatMap {
      case Some(cnsContext) =>
        for {
          transferContextOpt <- svcStore.getSvcTransferContextForRound(payment.payload.round)
          result <- transferContextOpt match {
            case Some(transferContext) =>
              val entryName = cnsContext.payload.name
              val entryUrl = cnsContext.payload.url
              val entryDescription = cnsContext.payload.description
              if (!CnsUtil.isValidEntryName(entryName)) {
                rejectPayment(
                  domainId,
                  payment,
                  s"entry name ($entryName) is not valid",
                  transferContext,
                )
              } else if (!CnsUtil.isValidEntryUrl(entryUrl)) {
                rejectPayment(
                  domainId,
                  payment,
                  s"entry url ($entryUrl) is not valid",
                  transferContext,
                )
              } else if (!CnsUtil.isValidEntryDescription(entryDescription)) {
                rejectPayment(
                  domainId,
                  payment,
                  s"entry description ($entryDescription) is not valid",
                  transferContext,
                )
              } else {
                svcStore
                  .listInitialPaymentConfirmationByCnsName(svParty, entryName)
                  .flatMap { confirmations =>
                    if (confirmations.isEmpty)
                      svcStore.lookupCnsEntryByName(entryName).flatMap {
                        case None =>
                          TaskSuccess(
                            s"skipping as there is no reason to reject this payment request."
                          ).pure[Future]
                        case Some(entry) =>
                          rejectPayment(
                            domainId,
                            payment,
                            s"entry already exists and owned by ${entry.payload.user}.",
                            transferContext,
                          )
                      }
                    else {
                      rejectPayment(
                        domainId,
                        payment,
                        s"initial payment collection has been confirmed for this cns name: ${confirmations
                            .map(_.contractId)}",
                        transferContext,
                      )
                    }
                  }
              }
            case None =>
              svcStore
                .getSvcTransferContext()
                .flatMap(
                  rejectPayment(
                    domainId,
                    payment,
                    s"round ${payment.payload.round} is no longer active.",
                    _,
                  )
                )
          }
        } yield result
      case None =>
        TaskSuccess(
          s"skipping as associated cns entry context not found: $contextId."
        ).pure[Future]
    }
  }

  private def rejectPayment(
      domainId: DomainId,
      payment: Contract[SubscriptionInitialPayment.ContractId, SubscriptionInitialPayment],
      reason: String,
      transferContext: AppTransferContext,
  )(implicit tc: TraceContext) = {
    val msg = s"rejecting initial subscription payment: $reason"
    logger.warn(msg)
    val cmd = payment.contractId.exerciseSubscriptionInitialPayment_Reject(transferContext)
    connection
      .submitWithResultNoDedup(
        Seq(svcParty),
        Seq(),
        cmd,
        domainId,
      )
      .map(_ => TaskSuccess(msg))
  }
}
