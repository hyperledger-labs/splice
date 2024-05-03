package com.daml.network.wallet.automation

import cats.syntax.traverse.*
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.{CNLedgerConnection, CommandPriority}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.AssignedContract
import com.daml.network.wallet.config.AutoAcceptTransfersConfig
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.util.{TopupUtil, ValidatorTopupConfig}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

// TODO(#12128): transform this trigger to an OnAssignedContractTrigger
class AutoAcceptTransferOffersTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CNLedgerConnection,
    autoAcceptTransfers: AutoAcceptTransfersConfig,
    scanConnection: ScanConnection,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    clock: Clock,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[AutoAcceptTransferOffersTrigger.Task] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AutoAcceptTransferOffersTrigger.Task]] =
    autoAcceptTransfers.fromParties
      .traverse { party =>
        for {
          filteredTransferOffers <- store.getOutstandingTransferOffers(
            Some(party),
            None,
          )
        } yield filteredTransferOffers
      }
      .map(_.flatten)

  override protected def completeTask(
      task: AutoAcceptTransferOffersTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      cmd = install.exercise(
        _.exerciseWalletAppInstall_TransferOffer_Accept(
          task.contractId
        )
      )
      // validatorTopupConfigO exist iff we are the validator party
      commandPriority <- validatorTopupConfigO match {
        case None => Future.successful(CommandPriority.Low)
        case Some(validatorTopupConfig) =>
          TopupUtil
            .hasSufficientFundsForTopup(scanConnection, store, validatorTopupConfig, clock)
            .map(if (_) CommandPriority.Low else CommandPriority.High): Future[CommandPriority]
      }
      _ <- connection
        .submit(
          Seq(store.key.validatorParty),
          Seq(),
          cmd,
          priority = commandPriority,
        )
        .noDedup
        .yieldResult()
    } yield {
      TaskSuccess(s"Accepted transfer offer")
    }
  }

  override protected def isStaleTask(task: AutoAcceptTransferOffersTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractById(transferOffersCodegen.TransferOffer.COMPANION)(task.contractId)
      .map(_.isEmpty)
}

object AutoAcceptTransferOffersTrigger {
  final type Task = AssignedContract[
    transferOffersCodegen.TransferOffer.ContractId,
    transferOffersCodegen.TransferOffer,
  ]
}
