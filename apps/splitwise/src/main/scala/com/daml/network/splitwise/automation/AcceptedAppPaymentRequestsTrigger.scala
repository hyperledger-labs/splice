package com.daml.network.splitwise.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AcceptedAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: SplitwiseStore,
    connection: CoinLedgerConnection,
    scanConnection: ScanConnection,
    // extra readAs rights, which are required to readAs the validatorParty and thus see the CoinRules
    // TODO(M3-82): once we have explicit disclosure: remove the need to fetch these extra readAs rights, which are there to enable using the CoinRules, which are only visible to the validatorParty
    readAsWithValidatorUser: Set[
      PartyId
    ],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      walletCodegen.AcceptedAppPayment.Contract,
      walletCodegen.AcceptedAppPayment.ContractId,
      walletCodegen.AcceptedAppPayment,
    ](store.acs, walletCodegen.AcceptedAppPayment.COMPANION) {

  override def processTask(
      payment: JavaContract[
        walletCodegen.AcceptedAppPayment.ContractId,
        walletCodegen.AcceptedAppPayment,
      ]
  )(implicit tc: TraceContext): Future[Some[String]] = {
    val provider = store.providerParty
    val sender = PartyId.tryFromProtoPrimitive(payment.payload.sender)
    val transferInProgressId = splitwiseCodegen.TransferInProgress.ContractId.unsafeFromInterface(
      payment.payload.deliveryOffer
    )
    store.lookupInstall(sender).flatMap {
      case QueryResult(_, None) =>
        val msg = s"Install contract not found for sender party $sender"
        logger.warn(msg)
        for {
          transferContext <- scanConnection.getAppTransferContext()
          cmd = payment.contractId.exerciseAcceptedAppPayment_Reject(transferContext)
          res <- connection
            .submitCommandsNoDedup(
              actAs = Seq(provider),
              readAs = Seq.empty,
              commands = cmd.commands.asScala.toSeq,
            )
            .map(_ => Some(s"rejected accepted app payment: $msg"))
        } yield res
      case QueryResult(_, Some(install)) =>
        for {
          transferContext <- scanConnection.getAppTransferContext()
          transferInProgress <- store.acs
            .lookupContractById(splitwiseCodegen.TransferInProgress.COMPANION)(transferInProgressId)
            .map(
              _.value.getOrElse(
                throw new IllegalStateException(
                  s"Invariant violation: transfer in progress $transferInProgressId not known"
                )
              )
            )
          group <- store.getGroup(
            PartyId.tryFromProtoPrimitive(transferInProgress.payload.group.owner),
            transferInProgress.payload.group.id,
          )
          cmd = install.contractId.exerciseSplitwiseInstall_CompleteTransfer(
            group.value.contractId,
            payment.contractId,
            transferContext,
          )
          _ <- connection.submitCommandsNoDedup(
            actAs = Seq(provider),
            readAs = readAsWithValidatorUser.toSeq,
            commands = cmd.commands.asScala.toSeq,
          )
        } yield Some("accepted payment and completed transfer")
    }
  }
}
