package com.daml.network.automation

import akka.stream.Materializer
import com.daml.error.ErrorCategory
import com.daml.error.utils.ErrorDetails
import com.daml.network.automation.{
  OnReadyForTransferInTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.{LedgerClient, TransferEvent}
import com.daml.network.store.CNNodeAppStore
import com.daml.network.store.MultiDomainAcsStore.TransferId
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.StatusRuntimeException
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TransferInTrigger(
    override protected val context: TriggerContext,
    store: CNNodeAppStore[_, _],
    connection: CNLedgerConnection,
    partyId: PartyId,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyForTransferInTrigger(
      store
    ) {

  override protected def completeTask(
      transferOut: TransferEvent.Out
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      outcome <-
        if (partyId == transferOut.submitter) {
          for {
            msg <- connection
              .submitTransferAndWaitNoDedup(
                submitter = partyId,
                command = LedgerClient.TransferCommand.In(
                  transferOutId = transferOut.transferOutId,
                  source = transferOut.source,
                  target = transferOut.target,
                ),
              )
              .map(_ =>
                show"Initiated transfer in of ${transferOut.contractId.contractId} from ${transferOut.source} to ${transferOut.target}"
              )
              .recoverWith { case TransferInTrigger.TransferCompletedException(_) =>
                store.multiDomainAcsStore.ingestionSink
                  .removeTransferOutIfBootstrap(
                    transferOut.contractId,
                    TransferId.fromTransferOut(transferOut),
                  )
                  .map(_ =>
                    show"Transfer in for ${transferOut.contractId.contractId} from ${transferOut.source} to ${transferOut.target} failed because the transfer was already completed, removed from transfer store"
                  )
              }
          } yield msg
        } else {
          Future.successful(
            show"Ignoring tranfer out of ${transferOut.contractId.contractId}, not initiated by our party"
          )
        }
    } yield TaskSuccess(outcome)
}

object TransferInTrigger {
  object TransferCompletedException {
    def unapply(ex: Throwable): Option[StatusRuntimeException] =
      ex match {
        case statusEx: StatusRuntimeException =>
          val errorCategory: Option[ErrorCategory] =
            ErrorCodeUtils.errorCategoryFromString(statusEx.getStatus.getDescription)
          val errorDetails = ErrorDetails.from(statusEx)
          val isTransferCompletedEx =
            errorCategory == Some(ErrorCategory.InvalidGivenCurrentSystemStateOther)
              && errorDetails.exists {
                case ErrorDetails.ErrorInfoDetail(errorCode, metadata) =>
                  errorCode == "TRANSFER_COMMAND_VALIDATION" &&
                  metadata.get("error").exists(_.contains("transfer already completed"))
                case _: ErrorDetails.ResourceInfoDetail | _: ErrorDetails.RetryInfoDetail |
                    _: ErrorDetails.RequestInfoDetail =>
                  false
              }
          Option.when(isTransferCompletedEx)(statusEx)
        case _ => None
      }
  }
}
