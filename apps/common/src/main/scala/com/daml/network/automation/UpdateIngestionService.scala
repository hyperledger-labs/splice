package com.daml.network.automation

import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CNLedgerConnection, CNLedgerSubscription, RetryProvider}
import com.daml.network.environment.ledger.api.{TransactionTreeUpdate, TransferUpdate}
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.store.MultiDomainAcsStore
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Ingestion for ACS and transfer stores.
  * We ingest them independently but we ensure that the acs store
  * is never caught up further than the transfer store to avoid losing
  * track of contracts on a transfer out.
  */
class UpdateIngestionService(
    ingestionTargetName: String,
    ingestionSink: MultiDomainAcsStore.IngestionSink,
    connection: CNLedgerConnection,
    override protected val retryProvider: RetryProvider,
    baseLoggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends LedgerIngestionService {

  private val filter = ingestionSink.ingestionFilter

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("ingestionLoopFor", ingestionTargetName)

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CNLedgerSubscription[?]] =
    for {
      lastIngestedOffset <- ingestionSink.initialize()
      subscribeFrom <- lastIngestedOffset match {
        case None =>
          for {
            offset <- connection.ledgerEnd().map(_.value)
            _ <- ingestAcsAndInFlight(offset)
          } yield offset
        case Some(offset) =>
          Future.successful(offset)
      }
    } yield new CNLedgerSubscription(
      source = updateSource(subscribeFrom),
      mapOperator = Flow[GetTreeUpdatesResponse].mapAsync(1)(process),
      retryProvider = retryProvider,
      timeouts = timeouts,
      loggerFactory = baseLoggerFactory.append("subsClient", this.getClass.getSimpleName),
    )

  private def process(
      msg: GetTreeUpdatesResponse
  )(implicit traceContext: TraceContext) = {
    for {
      _ <- msg.update match {
        case TransactionTreeUpdate(tree) =>
          waitForOffset(new LedgerOffset.Absolute(tree.getOffset))
        case TransferUpdate(_) =>
          // Transfers don't reliably advance the offset in the participant, so we don't synchronize on those.
          Future.unit
      }
      _ <- ingestionSink.ingestUpdate(msg.domainId, msg.update)
    } yield ()
  }

  private def updateSource(subscribeFrom: String) = {
    val participantOffset = ParticipantOffset(ParticipantOffset.Value.Absolute(subscribeFrom))

    connection.updates(participantOffset, filter.primaryParty)
  }

  private def ingestAcsAndInFlight(
      offset: String
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val javaOffset = ParticipantOffset.Value.Absolute(offset)
    for {
      // TODO(#5534): stream contracts instead of ingesting them as a single Seq
      (evs, tfs) <- connection.activeContracts(filter, javaOffset)
      _ <- ingestionSink.ingestAcs(
        offset,
        evs,
        tfs,
      )
    } yield ()
  }

  // TODO(#2728) Remove this once the multi-domain APIs are properly
  // integrated in the ledger API server. In the current state, the
  // multi-domain APIs can emit an update before the ledger API server
  // has updated all its caches. That can result in a
  // CONTRACT_NOT_FOUND error when we try to use that contract in a
  // submission.  By blocking on the ledger end update we regain that
  // synchronization at least for transactions.  Transfers don't
  // update the ledger offset in the ledger API server so for now we
  // ignore that.  The cases where this would cause issues are
  // sufficiently rare that this seems acceptable for now.
  private def waitForOffset(
      offset: LedgerOffset.Absolute
  )(implicit traceContext: TraceContext): Future[Unit] = {
    retryProvider.retryForAutomation(
      show"wait for offset $offset", {
        connection
          .participantLedgerEnd()
          .map {
            case endAbsolute: LedgerOffset.Absolute if endAbsolute.getOffset >= offset.getOffset =>
            case endOffset =>
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  show"Ledger end not yet caught up to $offset, current offset: $endOffset"
                )
                .asRuntimeException()
          }
      },
      logger,
    )
  }

  // Kick-off the ingestion
  startIngestion()
}
