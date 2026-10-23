// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import cats.data.NonEmptyList
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.PekkoUtil.syntax.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Materializer, RestartSettings}
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  RetryProvider,
  ServiceWithGuaranteedShutdown,
  SpliceLedgerConnection,
  SpliceMetrics,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import com.daml.metrics.api.MetricHandle.{Counter, LabeledMetricsFactory}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricQualification, MetricsContext}
import com.digitalasset.canton.lifecycle.UnlessShutdown

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
    clock: Clock,
    backoffClock: Clock,
    override protected val retryProvider: RetryProvider,
    packageVersionSupport: PackageVersionSupport,
    baseLoggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends RetryingService(config, backoffClock, "update ingestion") {

  private val metrics = new UpdateIngestionServiceMetrics(retryProvider.metricsFactory)

  private val filter = ingestionSink.ingestionFilter

  // Progress tracking for the stall watchdog. The ledger API stream can stop delivering without ever failing,
  // in which case the retry loop of RetryingService never kicks in and the store silently stops advancing.
  private val lastProgressNanos = new AtomicLong(System.nanoTime())
  private val lastIngestedOffset = new AtomicLong(-1L)
  private val stalled = new AtomicBoolean(false)

  override protected def isMakingProgress: Boolean = !stalled.get()

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("ingestionLoopFor", ingestionTargetName)

  override protected def instantiateService()(implicit
      traceContext: TraceContext
  ): Future[ServiceWithGuaranteedShutdown[?]] =
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
      _ = {
        // Only restart the watchdog window here: `stalled` must stay set until something is actually ingested.
        lastIngestedOffset.set(subscribeFrom)
        lastProgressNanos.set(System.nanoTime())
      }
    } yield new ServiceWithGuaranteedShutdown(
      source = batchSource(connection.updates(subscribeFrom, filter)),
      map = process,
      retryProvider = retryProvider,
      loggerFactory = baseLoggerFactory.append("subsClient", this.getClass.getSimpleName),
    )

  private def process(
      msgs: Seq[GetTreeUpdatesResponse]
  )(implicit traceContext: TraceContext): Future[Unit] = {
    // if paused, this step will backpressure the source
    waitForResume().flatMap { _ =>
      NonEmptyList.fromFoldable(msgs) match {
        case Some(batch) =>
          logger.debug(s"Processing batch of ${batch.size} elements")
          ingestionSink
            .ingestUpdateBatch(batch.map(_.updateOrCheckpoint))
            .map(_ => recordProgress(batch.last.updateOrCheckpoint.offset))
        case None =>
          logger.error(
            "Received empty batch of updates to ingest. This is never supposed to happen."
          )
          Future.unit
      }
    }
  }

  private def recordProgress(offset: Long): Unit = {
    lastIngestedOffset.set(offset)
    lastProgressNanos.set(System.nanoTime())
    stalled.set(false)
  }

  private def scheduleStallCheck(): Unit = {
    implicit val tc: TraceContext = TraceContext.empty
    retryProvider
      .scheduleAfterUnlessShutdown(
        checkForStall(),
        backoffClock,
        config.ingestion.stallCheckInterval,
        jitter = 0.1,
      )
      .unwrap
      .foreach {
        case UnlessShutdown.Outcome(_) => scheduleStallCheck()
        case UnlessShutdown.AbortedDueToShutdown => ()
      }
  }

  /** Restarts the current ingestion instance if it has not made progress for `stallTimeout` while the participant
    * has moved on. An idle ledger (ledger end == last ingested offset) is not a stall.
    */
  private def checkForStall()(implicit tc: TraceContext): Future[Unit] = {
    val stalledFor = (System.nanoTime() - lastProgressNanos.get()).nanos
    val stallTimeout = config.ingestion.stallTimeout.underlying
    val running = !isPaused && currentService.exists(_.isActive)
    if (!running || stalledFor < stallTimeout) Future.unit
    else
      connection
        .ledgerEnd()
        .map { ledgerEnd =>
          val ingested = lastIngestedOffset.get()
          if (UpdateIngestionService.hasStalled(stalledFor, stallTimeout, ledgerEnd, ingested)) {
            logger.warn(
              s"Ingestion of $ingestionTargetName made no progress for $stalledFor: ingested up to offset $ingested " +
                s"while the ledger end is at $ledgerEnd. Restarting the ingestion loop."
            )
            stalled.set(true)
            metrics.stallRestarts.inc()(MetricsContext.Empty)
            currentService.foreach(_.initiateShutdown())
          }
          // Give the (re)started instance a full window before checking again.
          lastProgressNanos.set(System.nanoTime())
        }
        .recover { case ex =>
          logger.info("Failed to check ingestion progress against the ledger end, will retry.", ex)
        }
  }

  private def ingestAcsAndInFlight(
      offset: Long
  )(implicit traceContext: TraceContext): Future[Unit] = {
    ingestionSink.ingestAcsStreamInBatches(
      batchSource(
        connection
          .activeContracts(
            filter,
            offset,
            RestartSettings(
              config.ingestion.activeContractsMinBackoff.underlying,
              config.ingestion.activeContractsMaxBackoff.underlying,
              config.ingestion.activeContractsRandomFactor,
            ),
            clock,
            packageVersionSupport,
          )
          .map { item =>
            logger.trace(s"Received active contract ${item.contractId} from participant")
            item
          }
          .buffered(
            metrics.activeContractsBufferSize,
            config.ingestion.activeContractsBufferSize,
          )
          .map { item =>
            logger.trace(s"Emitting active contract ${item.contractId} to Store")
            item
          }
      ),
      offset,
    )
  }

  private def batchSource[T](source: Source[T, NotUsed]): Source[Vector[T], NotUsed] =
    source.batch(config.ingestion.maxBatchSize.toLong, Vector(_))(_ :+ _)

  // Kick-off the ingestion
  start()
  scheduleStallCheck()
}

object UpdateIngestionService {

  /** The ingestion has stalled if it made no progress for at least `timeout` while the participant's ledger end is
    * ahead of the last ingested offset. If the ledger end has not moved past what we ingested the ledger is simply
    * idle, which is not a stall.
    */
  private[automation] def hasStalled(
      stalledFor: FiniteDuration,
      timeout: FiniteDuration,
      ledgerEnd: Long,
      lastIngestedOffset: Long,
  ): Boolean = stalledFor >= timeout && ledgerEnd > lastIngestedOffset
}

class UpdateIngestionServiceMetrics(metricsFactory: LabeledMetricsFactory) {
  val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "update-ingestion-service"
  val stallRestarts: Counter =
    metricsFactory.counter(
      MetricInfo(
        prefix :+ "stall-restarts",
        summary =
          "Number of times the ingestion loop was restarted because it stopped making progress.",
        qualification = MetricQualification.Errors,
      )
    )
  val activeContractsBufferSize: Counter =
    metricsFactory.counter(
      MetricInfo(
        prefix :+ "active-contracts-buffer-size",
        summary = "The buffer size for streaming ACS requests.",
        qualification = MetricQualification.Debug,
      )
    )
}
