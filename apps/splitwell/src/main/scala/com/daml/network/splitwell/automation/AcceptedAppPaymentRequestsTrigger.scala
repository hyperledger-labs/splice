package com.daml.network.splitwell.automation

import akka.stream.Materializer
import com.daml.ledger.api.v1.CommandsOuterClass
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AcceptedAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CNLedgerConnection,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      walletCodegen.AcceptedAppPayment.ContractId,
      walletCodegen.AcceptedAppPayment,
    ](
      store,
      walletCodegen.AcceptedAppPayment.COMPANION,
    ) {

  override def completeTask(
      payment: ReadyContract[
        walletCodegen.AcceptedAppPayment.ContractId,
        walletCodegen.AcceptedAppPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val provider = store.providerParty
    val transferInProgressId = splitwellCodegen.TransferInProgress.ContractId.unsafeFromInterface(
      payment.contract.payload.deliveryOffer
    )
    val round = payment.contract.payload.round
    def rejectPayment(
        reason: String,
        transferContext: v1.coin.AppTransferContext,
        domainId: DomainId,
        disclosedContracts: Seq[CommandsOuterClass.DisclosedContract],
    ) = {
      logger.warn(s"rejecting accepted app payment: $reason")
      val cmd = payment.contract.contractId.exerciseAcceptedAppPayment_Reject(transferContext)
      connection
        .submitWithResultNoDedup(Seq(store.providerParty), Seq(), cmd, domainId, disclosedContracts)
        .map(_ => TaskSuccess(s"rejected accepted app payment: $reason"))
    }
    for {
      transferContextE <- scanConnection
        .getAppTransferContextForRound(store.providerParty, round)
      result <- transferContextE match {
        case Right((transferContext, disclosedContracts)) =>
          for {
            transferInProgress <- store.multiDomainAcsStore
              .lookupContractByIdOnDomainOrRetry(splitwellCodegen.TransferInProgress.COMPANION)(
                payment.domain,
                transferInProgressId,
              )
              .map(
                _.getOrElse(
                  throw new IllegalStateException(
                    s"Invariant violation: transfer in progress $transferInProgressId not known"
                  )
                )
              )
            cmd = transferInProgress.contractId.exerciseTransferInProgress_CompleteTransfer(
              payment.contract.contractId,
              transferContext,
            )
            _ <- connection.submitCommandsNoDedup(
              actAs = Seq(provider),
              readAs = Seq(),
              commands = cmd.commands.asScala.toSeq,
              domainId = payment.domain,
              disclosedContracts = disclosedContracts,
            )
          } yield TaskSuccess("accepted payment and completed transfer")
        case Left(err) =>
          scanConnection
            .getAppTransferContext(store.providerParty)
            .flatMap { case (transferContext, disclosedContracts) =>
              rejectPayment(
                s"Round ${payment.contract.payload.round} is no longer active: $err",
                transferContext,
                payment.domain,
                disclosedContracts,
              )
            }

      }
    } yield result
  }
}
