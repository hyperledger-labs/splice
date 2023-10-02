package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.directory.DirectoryUtil
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.{Contract, DisclosedContracts, AssignedContract}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SubscriptionPaymentTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CNLedgerConnection,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      subsCodegen.SubscriptionPayment.ContractId,
      subsCodegen.SubscriptionPayment,
    ](
      store,
      subsCodegen.SubscriptionPayment.COMPANION,
    ) {

  override def completeTask(
      paymentReady: AssignedContract[
        subsCodegen.SubscriptionPayment.ContractId,
        subsCodegen.SubscriptionPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val AssignedContract(payment, domainId) = paymentReady
    val contextId = directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
      payment.payload.subscriptionData.context
    )
    val provider = store.providerParty
    def rejectPayment(
        reason: String,
        transferContext: cc.coin.AppTransferContext,
        disclosedContracts: DisclosedContracts.NE,
        log: String => Unit = logger.warn(_),
    ) = {
      log(s"rejecting subscription payment: $reason")
      val cmd = payment.exercise(_.exerciseSubscriptionPayment_Reject(transferContext))
      connection
        .submit(Seq(provider), Seq(), cmd)
        .withDisclosedContracts(disclosedContracts assertOnDomain domainId)
        .noDedup
        .yieldUnit()
        .map(_ => TaskSuccess(s"rejected subscription payment: $reason"))
    }
    def collectPayment(
        entry: Contract[
          directoryCodegen.DirectoryEntry.ContractId,
          directoryCodegen.DirectoryEntry,
        ],
        deduplicationOffset: String,
        transferContext: cc.coin.AppTransferContext,
        disclosedContracts: DisclosedContracts.NE,
    ) = {
      val cmd =
        contextId
          .exerciseDirectoryEntryContext_CollectEntryRenewalPayment(
            payment.contractId,
            entry.contractId,
            transferContext,
          )
      for {
        _ <- connection
          .submit(
            actAs = Seq(provider),
            readAs = Seq.empty,
            cmd,
          )
          .withDedup(
            commandId = DirectoryUtil.createDirectoryEntryCommandId(provider, entry.payload.name),
            deduplicationOffset = deduplicationOffset,
          )
          .withDisclosedContracts(disclosedContracts assertOnDomain domainId)
          .yieldUnit()
      } yield TaskSuccess("renewed directory entry.")
    }
    store.multiDomainAcsStore
      .lookupContractByIdOnDomain(directoryCodegen.DirectoryEntryContext.COMPANION)(
        domainId,
        contextId,
      )
      .flatMap {
        case Some(directoryEntryContext) =>
          for {
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
                  case QueryResult(offset, Some(entry)) =>
                    // collect the payment and renew the entry
                    collectPayment(
                      entry,
                      offset,
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
        case None =>
          Future.successful(TaskSuccess(s"skipping as subscription context $contextId not known."))
      }
  }
}
