package com.daml.network.automation

import com.daml.network.store.CoinAppStore
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import akka.stream.Materializer
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.daml.network.automation.{
  OnReadyForTransferInTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.{CoinLedgerConnection, LedgerClient}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TransferInTrigger(
    override protected val context: TriggerContext,
    store: CoinAppStore[_, _],
    connection: CoinLedgerConnection,
    domainId: DomainId,
    partyId: PartyId,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyForTransferInTrigger(
      store,
      domainId,
    ) {

  override protected def completeTask(
      transferOut: LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      outcome <-
        if (partyId == transferOut.submitter) {
          for {
            _ <- connection.submitTransferAndWaitNoDedup(
              submitter = partyId,
              command = LedgerClient.TransferCommand.In(
                transferOutId = transferOut.transferOutId,
                source = transferOut.source,
                target = transferOut.target,
              ),
            )
          } yield show"Initiated transfer in of ${transferOut.contractId.contractId} from ${transferOut.source} to ${transferOut.target}"
        } else {
          Future.successful(
            show"Ignoring tranfer out of ${transferOut.contractId.contractId}, not initiated by our party"
          )
        }
    } yield TaskSuccess(outcome)
}
