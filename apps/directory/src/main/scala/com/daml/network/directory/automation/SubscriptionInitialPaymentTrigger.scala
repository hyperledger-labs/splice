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
      payment: AssignedContract[
        subsCodegen.SubscriptionInitialPayment.ContractId,
        subsCodegen.SubscriptionInitialPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    def rejectPayment(
        reason: String,
        transferContext: cc.coinrules.AppTransferContext,
        disclosedContracts: DisclosedContracts.NE,
    ) = {
      logger.warn(s"rejecting initial subscription payment: $reason")
      val cmd = payment.exercise(_.exerciseSubscriptionInitialPayment_Reject(transferContext))
      connection
        .submit(Seq(store.providerParty), Seq(), cmd)
        .withDisclosedContracts(disclosedContracts)
        .noDedup
        .yieldResult()
        .map(_ => TaskSuccess(s"rejected initial subscription payment: $reason"))
    }
    def collectPaymentAndCreateEntry(
        contextId: directoryCodegen.DirectoryEntryContext.ContractId,
        entryName: String,
        deduplicationOffset: String,
        transferContext: cc.coinrules.AppTransferContext,
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
          .withDisclosedContracts(disclosedContracts assertOnDomain payment.domain)
          .yieldUnit()
      } yield TaskSuccess("created directory entry.")
    }

    store
      .lookupDirectoryEntryContext(
        payment.contract.payload.reference
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
                val entryDescription = context.contract.payload.description

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
                        context.contract.contractId,
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
          Future.successful(
            TaskSuccess(
              s"skipping as directory entry context for reference ${payment.contract.payload.reference} is not known."
            )
          )
      }

  }
}
