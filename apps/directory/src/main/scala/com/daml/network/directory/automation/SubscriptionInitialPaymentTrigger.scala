package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.directory.DirectoryUtil
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.MultiDomainAcsStore.{QueryResult, ReadyContract}
import com.daml.network.util.DisclosedContracts
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class SubscriptionInitialPaymentTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CNLedgerConnection,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      subsCodegen.SubscriptionInitialPayment.ContractId,
      subsCodegen.SubscriptionInitialPayment,
    ](
      store,
      subsCodegen.SubscriptionInitialPayment.COMPANION,
    ) {

  override def completeTask(
      readyPayment: ReadyContract[
        subsCodegen.SubscriptionInitialPayment.ContractId,
        subsCodegen.SubscriptionInitialPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val ReadyContract(payment, domainId) = readyPayment
    val contextId = directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
      payment.payload.subscriptionData.context
    )
    def rejectPayment(
        reason: String,
        transferContext: v1.coin.AppTransferContext,
        disclosedContracts: DisclosedContracts.NE,
    ) = {
      logger.warn(s"rejecting initial subscription payment: $reason")
      val cmd = payment.contractId.exerciseSubscriptionInitialPayment_Reject(transferContext)
      connection
        .submitWithResultNoDedup(
          Seq(store.providerParty),
          Seq(),
          cmd,
          domainId,
          disclosedContracts = disclosedContracts assertOnDomain domainId,
        )
        .map(_ => TaskSuccess(s"rejected initial subscription payment: $reason"))
    }
    def collectPayment(
        entryName: String,
        deduplicationOffset: String,
        transferContext: v1.coin.AppTransferContext,
        disclosedContracts: DisclosedContracts.NE,
    ) = {
      val cmd =
        contextId
          .exerciseDirectoryEntryContext_CollectInitialEntryPayment(
            payment.contractId,
            transferContext,
          )
          .commands
      for {
        _ <- connection
          .submitCommands(
            actAs = Seq(store.providerParty),
            readAs = Seq.empty,
            commands = cmd.asScala.toSeq,
            commandId = DirectoryUtil.createDirectoryEntryCommandId(store.providerParty, entryName),
            deduplicationOffset = deduplicationOffset,
            domainId = domainId,
            disclosedContracts = disclosedContracts assertOnDomain domainId,
          )
      } yield TaskSuccess("created directory entry.")
    }
    for {
      context <- store.multiDomainAcsStore
        .lookupContractByIdOnDomain(directoryCodegen.DirectoryEntryContext.COMPANION)(
          domainId,
          contextId,
        )
        .map(
          _.getOrElse(
            throw new IllegalStateException(
              s"Invariant violation: subscription context $contextId not known"
            )
          )
        )
      transferContextE <- scanConnection.getAppTransferContextForRound(
        store.svcParty,
        payment.payload.round,
      )
      result <- transferContextE match {
        case Right((transferContext, disclosedContracts)) =>
          // TODO(M3-03): understand what kind of assertions are worth checking here for defensive programming
          val entryName = context.payload.name
          // check whether the entry already exists
          store.lookupEntryByNameWithOffset(entryName).flatMap {
            case QueryResult(_, Some(entry)) =>
              rejectPayment(
                s"entry already exists and owned by ${entry.payload.user}.",
                transferContext,
                disclosedContracts,
              )
            case QueryResult(offset, None) =>
              // collect the payment and create the entry
              collectPayment(
                entryName,
                offset,
                transferContext,
                disclosedContracts,
              )
          }

        case Left(err) =>
          scanConnection
            .getAppTransferContext(store.svcParty)
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
