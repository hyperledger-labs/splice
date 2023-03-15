package com.daml.network.automation

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.{CoinLedgerConnection, CoinLedgerSubscription, CoinRetries}
import com.daml.network.store.{AcsStore, TransferStore}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Ingestion for ACS and transfer stores.
  * We ingest them independently but we ensure that the acs store
  * is never caught up further than the transfer store to avoid losing
  * track of contracts on a transfer out.
  */
class UpdateIngestionService(
    ingestionTargetName: String,
    acsIngestionSink: AcsStore.IngestionSink,
    transferIngestionSink: TransferStore.IngestionSink,
    domain: DomainId,
    connection: CoinLedgerConnection,
    override protected val retryProvider: CoinRetries,
    baseLoggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends LedgerIngestionService {

  private val acsFilter = acsIngestionSink.ingestionFilter
  private val transferFilter = transferIngestionSink.ingestionFilter

  require(
    acsFilter.primaryParty == transferFilter,
    "acs and transfer filter are for the same party",
  )

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("ingestionLoopFor", ingestionTargetName)

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[CoinLedgerSubscription[?]] =
    for {
      lastIngestedAcsOffset <- acsIngestionSink.getLastIngestedOffset
      lastIngestedTransferOffset <- transferIngestionSink.getLastIngestedOffset(domain)
      subscribeFrom <- (lastIngestedAcsOffset, lastIngestedTransferOffset) match {
        case (None, None) =>
          for {
            offset <- connection.ledgerEnd(domain).map {
              // Documented as always being aboslute
              case absolute: LedgerOffset.Absolute => absolute.getOffset
              case _ => sys.error("expected absolute offset")
            }
            _ <- ingestInFlight(offset)
            _ <- ingestAcs(offset)
          } yield offset
        case (None, Some(offset)) =>
          ingestAcs(offset).map(_ => offset)
        case (Some(off), None) =>
          val msg =
            show"Invalid state: ACS for domain $domain caught up to offset $off but no offset in transfer store"
          logger.error(msg)
          Future.failed(new IllegalStateException(msg))
        case (Some(acsOffset), Some(transferOffset)) =>
          if (acsOffset > transferOffset) {
            val msg =
              show"Invalid state: ACS for domain $domain caught up to offset $acsOffset but transfer store only caught up to offset $transferOffset"
            logger.error(msg)
            Future.failed(new IllegalStateException(msg))
          } else if (acsOffset < transferOffset) {
            logger.info(
              show"Catching up ACS for domain $domain from offset $acsOffset to $transferOffset"
            )
            catchupAcs(acsOffset, transferOffset).map { _ =>
              logger.info(show"Completed ACS catchup for domain $domain to offset $transferOffset")
              transferOffset
            }
          } else {
            Future.successful(transferOffset)
          }
      }
    } yield connection.subscribeAsync(
      this.getClass.getSimpleName,
      loggerFactory,
      new LedgerOffset.Absolute(subscribeFrom),
      None,
      acsFilter.primaryParty,
      domain,
    ) { update =>
      for {
        _ <- transferIngestionSink.ingestUpdate(domain, update)
        _ <- acsIngestionSink.ingestUpdate(update)
      } yield ()
    }

  /** Ingests the ACS at the given offset */
  private def ingestAcs(
      offset: String
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      // TODO(M3-83): stream contracts instead of ingesting them as a single Seq
      evs <- connection.activeContracts(domain, acsFilter, Some(new LedgerOffset.Absolute(offset)))
      _ <- acsIngestionSink.ingestActiveContracts(evs)
      _ <- acsIngestionSink.switchToIngestingTransactions(offset)
    } yield ()
  }

  private def catchupAcs(
      startOffset: String,
      endOffset: String,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val subscription = connection.subscribeAsync(
      this.getClass.getSimpleName,
      loggerFactory,
      new LedgerOffset.Absolute(startOffset),
      Some(new LedgerOffset.Absolute(endOffset)),
      acsFilter.primaryParty,
      domain,
    )(acsIngestionSink.ingestUpdate(_))
    subscription.completed.map(_ => ())
  }

  private def ingestInFlight(
      offset: String
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      tfs <- connection.getInFlightTransfers(
        domain,
        transferIngestionSink.ingestionFilter,
        Some(new LedgerOffset.Absolute(offset)),
      )
      _ <- transferIngestionSink.ingestInFlightTransfers(domain, tfs)
      _ <- transferIngestionSink.switchToIngestingUpdates(domain, offset)
    } yield ()
  }

  // Kick-off the ingestion
  startIngestion()
}
