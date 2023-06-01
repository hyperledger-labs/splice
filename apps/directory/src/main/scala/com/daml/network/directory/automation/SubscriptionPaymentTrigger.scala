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
import com.daml.network.util.{Contract, DisclosedContracts}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class SubscriptionPaymentTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CNLedgerConnection,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      subsCodegen.SubscriptionPayment.ContractId,
      subsCodegen.SubscriptionPayment,
    ](
      store,
      subsCodegen.SubscriptionPayment.COMPANION,
    ) {

  override def completeTask(
      paymentReady: ReadyContract[
        subsCodegen.SubscriptionPayment.ContractId,
        subsCodegen.SubscriptionPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val ReadyContract(payment, domainId) = paymentReady
    val contextId = directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
      payment.payload.subscriptionData.context
    )
    val provider = store.providerParty
    def rejectPayment(
        reason: String,
        transferContext: v1.coin.AppTransferContext,
        disclosedContracts: DisclosedContracts,
        log: String => Unit = logger.warn(_),
    ) = {
      log(s"rejecting subscription payment: $reason")
      val cmd = payment.contractId.exerciseSubscriptionPayment_Reject(transferContext).commands
      connection
        .submitCommandsNoDedup(
          Seq(provider),
          Seq(),
          cmd.asScala.toSeq,
          domainId,
          disclosedContracts,
        )
        .map(_ => TaskSuccess(s"rejected subscription payment: $reason"))
    }
    def collectPayment(
        entry: Contract[
          directoryCodegen.DirectoryEntry.ContractId,
          directoryCodegen.DirectoryEntry,
        ],
        deduplicationOffset: String,
        transferContext: v1.coin.AppTransferContext,
        disclosedContracts: DisclosedContracts,
    ) = {
      val cmd =
        contextId
          .exerciseDirectoryEntryContext_CollectEntryRenewalPayment(
            payment.contractId,
            entry.contractId,
            transferContext,
          )
          .commands
      for {
        _ <- connection
          .submitCommands(
            actAs = Seq(provider),
            readAs = Seq.empty,
            commands = cmd.asScala.toSeq,
            commandId = DirectoryUtil.createDirectoryEntryCommandId(provider, entry.payload.name),
            deduplicationOffset = deduplicationOffset,
            domainId = domainId,
            disclosedContracts = disclosedContracts,
          )
      } yield TaskSuccess("renewed directory entry.")
    }
    for {
      directoryEntryContext <- store.multiDomainAcsStore
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
          val entryName = directoryEntryContext.payload.name
          // check whether the entry exists
          store.lookupEntryByNameWithOffset(entryName).flatMap {
            case result @ QueryResult(_, Some(entry)) =>
              // collect the payment and renew the entry
              collectPayment(
                entry,
                result.deduplicationOffset,
                transferContext,
                disclosedContracts,
              )
            case QueryResult(_, None) => {
              if (context.clock.now.toInstant.isBefore(payment.payload.thisPaymentDueAt)) {
                rejectPayment("entry doesn't exist.", transferContext, disclosedContracts)
              } else {
                // If the entry doesn't exist, and the payment is now past due, then
                // probably the ExpiredDirectoryEntryTrigger cleaned it up before this trigger
                // had a chance to renew it. We reject the payment but only log this at an INFO level
                // rather than a warning.
                rejectPayment(
                  "entry has already been expired",
                  transferContext,
                  disclosedContracts,
                  logger.info(_),
                )
              }
            }
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
