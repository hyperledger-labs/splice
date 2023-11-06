package com.daml.network.splitwell.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  TaskOutcome,
  TriggerContext,
  OnAssignedContractTrigger,
  TaskSuccess,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.util.{DisclosedContracts, AssignedContract}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class AcceptedAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CNLedgerConnection,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      walletCodegen.AcceptedAppPayment.ContractId,
      walletCodegen.AcceptedAppPayment,
    ](
      store,
      walletCodegen.AcceptedAppPayment.COMPANION,
    ) {

  override def completeTask(
      payment: AssignedContract[
        walletCodegen.AcceptedAppPayment.ContractId,
        walletCodegen.AcceptedAppPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val provider = store.providerParty
    val round = payment.payload.round
    def rejectPayment(
        reason: String,
        transferContext: cc.coinrules.AppTransferContext,
        disclosedContracts: DisclosedContracts.NE,
    ) = {
      logger.warn(s"rejecting accepted app payment: $reason")
      val cmd = payment.exercise(_.exerciseAcceptedAppPayment_Reject(transferContext))
      connection
        .submit(Seq(store.providerParty), Seq(), cmd)
        .withDisclosedContracts(disclosedContracts)
        .noDedup
        .yieldResult()
        .map(_ => TaskSuccess(s"rejected accepted app payment: $reason"))
    }
    for {
      transferContextE <- scanConnection
        .getAppTransferContextForRound(store.providerParty, round)
      result <- transferContextE match {
        case Right((transferContext, disclosedContracts)) =>
          for {
            transferInProgress <- store
              .lookupTransferInProgress(payment.payload.reference)
              .map(
                _.value.getOrElse(
                  throw new IllegalStateException(
                    show"Invariant violation: no transfer-in-progress contract found for payment request ${payment.payload.reference}"
                  )
                )
              )
            cmd = transferInProgress.exercise(
              _.exerciseTransferInProgress_CompleteTransfer(
                payment.contractId,
                transferContext,
              )
            )
            _ <- connection
              .submit(actAs = Seq(provider), readAs = Seq(), cmd)
              .withDisclosedContracts(disclosedContracts assertOnDomain payment.domain)
              .noDedup
              .yieldUnit()
          } yield TaskSuccess("accepted payment and completed transfer")
        case Left(err) =>
          scanConnection
            .getAppTransferContext(store.providerParty)
            .flatMap { case (transferContext, disclosedContracts) =>
              rejectPayment(
                s"Round ${payment.payload.round} is no longer active: $err",
                transferContext,
                disclosedContracts,
              )
            }

      }
    } yield result
  }
}
