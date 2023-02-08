package com.daml.network.automation

import com.daml.network.store.DomainStore
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import akka.stream.Materializer
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.DomainAlias
import com.daml.network.automation.{OnTransferOutTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.{CoinLedgerConnection, LedgerClient}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TransferInTrigger(
    override protected val context: TriggerContext,
    domains: DomainStore,
    connection: CoinLedgerConnection,
    globalDomain: DomainAlias,
    domainId: DomainId,
    partyId: PartyId,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnTransferOutTrigger(
      connection,
      domainId,
      partyId,
    ) {

  override protected def completeTask(
      transferOut: LedgerClient.GetTreeUpdatesResponse.Transfer[
        LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      globalDomainId <- domains.getDomainId(globalDomain)
      outcome <-
        if (partyId == transferOut.submitter) {
          for {
            _ <- connection.submitTransferNoDedup(
              submitter = partyId,
              command = LedgerClient.TransferCommand.In(
                transferOutId = transferOut.event.transferOutId,
                source = transferOut.event.source,
                target = transferOut.event.target,
              ),
            )
          } yield show"Initiated transfer in of ${transferOut.event.contractId.contractId} from ${transferOut.event.source} to ${transferOut.event.target}"
        } else {
          Future.successful(
            show"Ignoring tranfer out of ${transferOut.event.contractId.contractId}, not initiated by our party"
          )
        }
    } yield TaskSuccess(outcome)
}
