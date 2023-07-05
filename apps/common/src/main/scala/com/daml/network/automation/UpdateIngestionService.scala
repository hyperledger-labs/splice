package com.daml.network.automation

import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.network.environment.{CNLedgerConnection, CNLedgerSubscription, RetryProvider}
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.store.MultiDomainAcsStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
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
    ingestFromParticipantBegin: Boolean,
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
            offset <-
              if (ingestFromParticipantBegin) {
                val participantBegin = ParticipantOffset(
                  ParticipantOffset.Value.Boundary(
                    ParticipantOffset.ParticipantBoundary.PARTICIPANT_BEGIN
                  )
                )
                ingestionSink
                  .ingestAcs(
                    MultiDomainAcsStore.fromParticipantOffset(participantBegin),
                    Seq.empty,
                    Seq.empty,
                  )
                  .map(_ => participantBegin)
              } else
                for {
                  acsOffset <- connection.ledgerEnd()
                  _ <- ingestAcsAndInFlight(acsOffset.value)
                } yield ParticipantOffset(acsOffset)
          } yield offset
        case Some(offset) =>
          Future.successful(MultiDomainAcsStore.toParticipantOffset(offset))
      }
    } yield new CNLedgerSubscription(
      source = connection.updates(subscribeFrom, filter.primaryParty),
      mapOperator = Flow[GetTreeUpdatesResponse].mapAsync(1)(process),
      retryProvider = retryProvider,
      loggerFactory = baseLoggerFactory.append("subsClient", this.getClass.getSimpleName),
    )

  private def process(
      msg: GetTreeUpdatesResponse
  )(implicit traceContext: TraceContext) = ingestionSink.ingestUpdate(msg.domainId, msg.update)

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

  // Kick-off the ingestion
  startIngestion()
}
