package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
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
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.{DisclosedContracts, AssignedContract}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SubscriptionInitialPaymentTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CNLedgerConnection,
    scanConnection: ScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      subsCodegen.SubscriptionInitialPayment.ContractId,
      subsCodegen.SubscriptionInitialPayment,
    ](
      store,
      subsCodegen.SubscriptionInitialPayment.COMPANION,
    ) {

  override def completeTask(
      readyPayment: AssignedContract[
        subsCodegen.SubscriptionInitialPayment.ContractId,
        subsCodegen.SubscriptionInitialPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val AssignedContract(payment, domainId) = readyPayment
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
    def collectPaymentAndCreateEntry(
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
      for {
        _ <- connection
          .submit(
            actAs = Seq(store.providerParty),
            readAs = Seq.empty,
            cmd,
          )
          .withDedup(
            commandId = DirectoryUtil.createDirectoryEntryCommandId(store.providerParty, entryName),
            deduplicationOffset = deduplicationOffset,
          )
          .withDisclosedContracts(disclosedContracts assertOnDomain domainId)
          .yieldUnit()
      } yield TaskSuccess("created directory entry.")
    }

    store.multiDomainAcsStore
      .lookupContractByIdOnDomain(directoryCodegen.DirectoryEntryContext.COMPANION)(
        domainId,
        contextId,
      )
      .flatMap {
        case Some(context) =>
          for {
            transferContextE <- scanConnection.getAppTransferContextForRound(
              store.svcParty,
              payment.payload.round,
            )
            result <- transferContextE match {
              case Right((transferContext, disclosedContracts)) =>
                // TODO(M3-03): understand what kind of assertions are worth checking here for defensive programming
                val entryName = context.payload.name
                val entryUrl = context.payload.url
                val entryDescription = context.payload.description

                if (!DirectoryUtil.isValidEntryName(entryName)) {
                  rejectPayment(
                    s"entry name ($entryName) is not valid",
                    transferContext,
                    disclosedContracts,
                  )
                } else if (!DirectoryUtil.isValidEntryUrl(entryUrl)) {
                  rejectPayment(
                    s"entry url ($entryUrl) is not valid",
                    transferContext,
                    disclosedContracts,
                  )
                } else if (!DirectoryUtil.isValidEntryDescription(entryDescription)) {
                  rejectPayment(
                    s"entry description ($entryDescription) is not valid",
                    transferContext,
                    disclosedContracts,
                  )
                } else {
                  // check whether the entry already exists
                  store.lookupEntryByNameWithOffset(entryName).flatMap {
                    case QueryResult(_, Some(entry)) =>
                      rejectPayment(
                        s"entry already exists and owned by ${entry.payload.user}.",
                        transferContext,
                        disclosedContracts,
                      )
                    case QueryResult(offset, None) =>
                      collectPaymentAndCreateEntry(
                        entryName,
                        offset,
                        transferContext,
                        disclosedContracts,
                      )
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
