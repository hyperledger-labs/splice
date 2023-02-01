package com.daml.network.splitwise.automation

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.DomainAlias
import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.store.SplitwiseStore
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
) extends OnCreateTrigger[
      walletCodegen.AcceptedAppPayment.Contract,
      walletCodegen.AcceptedAppPayment.ContractId,
      walletCodegen.AcceptedAppPayment,
    ](store.acs, walletCodegen.AcceptedAppPayment.COMPANION) {

  override protected val source: Source[
    JavaContract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment],
    NotUsed,
  ] =
    // TODO (#2613) Remove this override once we have multi-domain stores.
    if (globalDomain == splitwiseDomain) {
      store.acs.streamContracts(walletCodegen.AcceptedAppPayment.COMPANION)
    } else {
      Source
        .lazyFutureSource(() =>
          store.domains.signalWhenConnected(globalDomain).map { domainId =>
            connection
              .updateCreates(
                domainId,
                store.providerParty,
                walletCodegen.AcceptedAppPayment.COMPANION,
              )
              .map(JavaContract.fromCodegenContract(_))
          }
        )
        .mapMaterializedValue(_ => NotUsed)
    }

  override final protected def isStaleTask(
      task: JavaContract[
        walletCodegen.AcceptedAppPayment.ContractId,
        walletCodegen.AcceptedAppPayment,
      ]
  )(implicit tc: TraceContext): Future[Boolean] =
    // TODO (#2613) Remove this override once we have multi-domain stores.
    if (globalDomain == splitwiseDomain) {
      super.isStaleTask(task)
    } else {
      Future.successful(false)
    }

  def lookupTransferInProgress(
      cid: ContractId[splitwiseCodegen.TransferInProgress]
  ): Future[Option[JavaContract[
    splitwiseCodegen.TransferInProgress.ContractId,
    splitwiseCodegen.TransferInProgress,
  ]]] =
    // TODO (#2613) Remove this override once we have multi-domain stores.
    if (globalDomain == splitwiseDomain) {
      store.acs.lookupContractById(splitwiseCodegen.TransferInProgress.COMPANION)(cid)
    } else {
      for {
        domainId <- store.domains.getDomainId(globalDomain)
        allTransferInProgress <- connection.activeContracts(
          domainId,
          store.providerParty,
          splitwiseCodegen.TransferInProgress.COMPANION,
        )
      } yield {
        allTransferInProgress.find(c => c.id == cid).map(JavaContract.fromCodegenContract(_))
      }
    }

  override def completeTask(
      payment: JavaContract[
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
      transferInProgress <- lookupTransferInProgress(
        transferInProgressId
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
