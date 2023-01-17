package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.directory.DirectoryUtil
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class SubscriptionPaymentTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CoinLedgerConnection,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      subsCodegen.SubscriptionPayment.Contract,
      subsCodegen.SubscriptionPayment.ContractId,
      subsCodegen.SubscriptionPayment,
    ](store.acs, subsCodegen.SubscriptionPayment.COMPANION) {

  override def completeTask(
      payment: JavaContract[
        subsCodegen.SubscriptionPayment.ContractId,
        subsCodegen.SubscriptionPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val contextId = directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
      payment.payload.subscriptionData.context
    )
    val provider = store.providerParty
    def rejectPayment(
        reason: String,
        transferContext: v1.coin.AppTransferContext,
        domainId: DomainId,
    ) = {
      logger.warn(s"rejecting subscription payment: $reason")
      val cmd = payment.contractId.exerciseSubscriptionPayment_Reject(transferContext).commands
      connection
        .submitCommandsNoDedup(Seq(provider), Seq(), cmd.asScala.toSeq, domainId)
        .map(_ => TaskSuccess(s"rejected subscription payment: $reason"))
    }
    def collectPayment(
        entry: JavaContract[
          directoryCodegen.DirectoryEntry.ContractId,
          directoryCodegen.DirectoryEntry,
        ],
        offset: String,
        transferContext: v1.coin.AppTransferContext,
        domainId: DomainId,
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
            deduplicationOffset = offset,
            domainId = domainId,
          )
      } yield TaskSuccess("renewed directory entry.")
    }
    for {
      domainId <- store.domains.getUniqueDomainId()
      context <- store.acs
        .lookupContractById(directoryCodegen.DirectoryEntryContext.COMPANION)(contextId)
        .map(
          _.getOrElse(
            throw new IllegalStateException(
              s"Invariant violation: subscription context $contextId not known"
            )
          )
        )
      transferContext <- scanConnection.getAppTransferContext()
      // TODO(M3-03): understand what kind of assertions are worth checking here for defensive programming
      entryName = context.payload.name
      // check whether the entry exists
      result <- store.lookupEntryByNameWithOffset(entryName).flatMap {
        case QueryResult(off, Some(entry)) =>
          // collect the payment and renew the entry
          collectPayment(entry, off, transferContext, domainId)
        case QueryResult(_, None) =>
          rejectPayment("entry doesn't exist.", transferContext, domainId)
      }
    } yield result
  }
}
