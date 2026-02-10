// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import cats.data.NonEmptyList
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.google.common.annotations.VisibleForTesting
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.environment.{
  RetryProvider,
  SpliceLedgerConnection,
  SpliceLedgerSubscription,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

/** Ingestion for ACS and transfer stores.
  * We ingest them independently but we ensure that the acs store
  * is never caught up further than the transfer store to avoid losing
  * track of contracts on a unassign.
  */
class UpdateIngestionService(
    ingestionTargetName: String,
    ingestionSink: MultiDomainAcsStore.IngestionSink,
    connection: SpliceLedgerConnection,
    config: AutomationConfig,
    backoffClock: Clock,
    override protected val retryProvider: RetryProvider,
    baseLoggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends LedgerIngestionService(config, backoffClock) {

  private val filter = ingestionSink.ingestionFilter

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("ingestionLoopFor", ingestionTargetName)

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[SpliceLedgerSubscription[?]] =
    for {
      ingestionStart <- ingestionSink.initialize()
      subscribeFrom <- ingestionStart match {
        case IngestionStart.ResumeAtOffset(offset) =>
          logger.debug(s"Resuming ingestion from offset $offset")
          Future.successful(offset)
        case IngestionStart.InitializeAcsAtOffset(offset) =>
          logger.debug(s"Initializing ACS and starting ingestion from offset $offset")
          for {
            _ <- ingestAcsAndInFlight(offset)
          } yield offset
        case IngestionStart.UpdateHistoryInitAtLatestPrunedOffset =>
          for {
            participantBegin <- connection.latestPrunedOffset()
            _ = logger.debug(
              s"Starting ingestion from participant begin at $participantBegin"
            )
            /** * This only applies to UpdateHistory. Does not load any ACS. Anything before the latest pruned offset:
              * - Scan: it will be backfilled.
              * - Wallet: won't miss any updates, because all updates visible to the wallet party only appear
              *           after you onboard that party through the wallet app. Does not backfill.
              */
            _ <- ingestionSink.ingestAcsStreamInBatches(
              Source.empty,
              participantBegin,
            )
          } yield participantBegin
        case IngestionStart.InitializeAcsAtLatestOffset =>
          for {
            acsOffset <- connection.ledgerEnd()
            _ = logger.debug(s"Starting ingestion from ledger end at $acsOffset")
            _ <- ingestAcsAndInFlight(acsOffset)
          } yield acsOffset
      }
    } yield new SpliceLedgerSubscription(
      source = batchSource(connection.updates(subscribeFrom, filter)),
      map = process,
      retryProvider = retryProvider,
      loggerFactory = baseLoggerFactory.append("subsClient", this.getClass.getSimpleName),
    )

  private def process(
      msgs: Seq[GetTreeUpdatesResponse]
  )(implicit traceContext: TraceContext): Future[Unit] = {
    // if paused, this step will backpressure the source
    waitForResumePromise.future.flatMap { _ =>
      NonEmptyList.fromFoldable(msgs) match {
        case Some(batch) =>
          logger.debug(s"Processing batch of ${batch.size} elements")
          ingestionSink.ingestUpdateBatch(batch.map(_.updateOrCheckpoint))
        case None =>
          logger.error(
            "Received empty batch of updates to ingest. This is never supposed to happen."
          )
          Future.unit
      }
    }
  }

  private def ingestAcsAndInFlight(
      offset: Long
  )(implicit traceContext: TraceContext): Future[Unit] = {
    ingestionSink.ingestAcsStreamInBatches(
      batchSource(connection.activeContracts(filter, offset)),
      offset,
    )
  }

  private def batchSource[T](source: Source[T, NotUsed]): Source[Vector[T], NotUsed] =
    source.batch(config.ingestion.maxBatchSize.toLong, Vector(_))(_ :+ _)

  // Kick-off the ingestion
  startIngestion()

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var waitForResumePromise = Promise.successful(())

  /** Note that any in-flight events being processed when `pause` is called will still be processed.
    */
  @VisibleForTesting
  def pause(): Future[Unit] = blocking {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      logger.info("Pausing UpdateIngestionService.")
      blocking {
        synchronized {
          if (waitForResumePromise.isCompleted) {
            waitForResumePromise = Promise()
          }
          Future.successful(())
        }
      }
    }
  }

  @VisibleForTesting
  def resume(): Unit = blocking {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      logger.info("Resuming UpdateIngestionService.")
      blocking {
        synchronized {
          val _ = waitForResumePromise.trySuccess(())
        }
      }
    }
  }
}
