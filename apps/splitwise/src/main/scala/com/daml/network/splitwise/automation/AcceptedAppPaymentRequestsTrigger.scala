package com.daml.network.splitwise.automation

import com.digitalasset.canton.DomainAlias
import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AcceptedAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: SplitwiseStore,
    connection: CoinLedgerConnection,
    globalDomain: DomainAlias,
    splitwiseDomain: DomainAlias,
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
) extends OnCreateTrigger.Template[
      walletCodegen.AcceptedAppPayment.Contract,
      walletCodegen.AcceptedAppPayment.ContractId,
      walletCodegen.AcceptedAppPayment,
    ](
      store,
      () => store.domains.signalWhenConnected(globalDomain),
      walletCodegen.AcceptedAppPayment.COMPANION,
    ) {

  override def completeTask(
      payment: Contract[
        walletCodegen.AcceptedAppPayment.ContractId,
        walletCodegen.AcceptedAppPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val provider = store.providerParty
    val transferInProgressId = splitwiseCodegen.TransferInProgress.ContractId.unsafeFromInterface(
      payment.payload.deliveryOffer
    )
    for {
      domainId <- store.domains.getDomainId(globalDomain)
      transferContext <- scanConnection.getAppTransferContext(store.providerParty)
      transferInProgress <- store
        .acs(domainId)
        .flatMap(
          _.lookupContractById(splitwiseCodegen.TransferInProgress.COMPANION)(transferInProgressId)
        )
        .map(
          _.getOrElse(
            throw new IllegalStateException(
              s"Invariant violation: transfer in progress $transferInProgressId not known"
            )
          )
        )
      cmd = transferInProgress.contractId.exerciseTransferInProgress_CompleteTransfer(
        payment.contractId,
        transferContext,
      )
      _ <- connection.submitCommandsNoDedup(
        actAs = Seq(provider),
        readAs = readAsWithValidatorUser.toSeq,
        commands = cmd.commands.asScala.toSeq,
        domainId = domainId,
      )
    } yield TaskSuccess("accepted payment and completed transfer")
  }
}
