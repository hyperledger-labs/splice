package com.daml.network.wallet.automation

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import akka.stream.Materializer
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.DomainAlias
import com.daml.network.automation.{OnTransferOutTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.{CoinLedgerConnection, LedgerClient}
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TransferInTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CoinLedgerConnection,
    globalDomain: DomainAlias,
    domainId: DomainId,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnTransferOutTrigger(
      connection,
      domainId,
      store.key.endUserParty,
    ) {

  override protected lazy val loggerFactory: NamedLoggerFactory =
    super.loggerFactory.append("domainId", domainId.toProtoPrimitive)

  override protected def completeTask(
      transferOut: LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      globalDomainId <- store.domains.getDomainId(globalDomain)
      outcome <-
        for {
          _ <- connection.submitTransferNoDedup(
            submitter = store.key.endUserParty,
            command = LedgerClient.TransferCommand.In(
              transferOutId = transferOut.transferOutId,
              source = transferOut.source,
              target = transferOut.target,
            ),
          )
        } yield show"Initiated transfer in of ${transferOut.contractId.contractId} from ${transferOut.source} to ${transferOut.target}"

    } yield TaskSuccess(outcome)
}
