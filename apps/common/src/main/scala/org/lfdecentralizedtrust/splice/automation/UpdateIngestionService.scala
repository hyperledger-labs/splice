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
import org.lfdecentralizedtrust.splice.automation.UpdateIngestionService.IngestionProgress
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import scala.concurrent.{ExecutionContext, Future}
import com.daml.metrics.api.MetricHandle.{Counter, LabeledMetricsFactory}
import com.daml.metrics.api.{MetricInfo, MetricName, MetricQualification, MetricsContext}
import com.digitalasset.canton.discard.Implicits.DiscardOps
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
  // All of it lives in one immutable value so that readers always see a consistent snapshot, and so that a
  // stall can be claimed with a compare-and-set that fails if anything was ingested since the snapshot.
  private val progress = new AtomicReference(
    IngestionProgress(offset = -1L, atNanos = System.nanoTime())
  )
  private val stallCheckInFlight = new AtomicBoolean(false)

  override protected def isMakingProgress: Boolean = !progress.get().stalled

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
      // Restart the watchdog window for the new stream. `stalled` stays set until something is actually ingested.
      _ = progress.updateAndGet(_.copy(offset = subscribeFrom, atNanos = System.nanoTime()))
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

  private def recordProgress(offset: Long): Unit =
    progress.set(IngestionProgress(offset = offset, atNanos = System.nanoTime()))

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
      .onComplete {
        case Success(UnlessShutdown.AbortedDueToShutdown) => ()
        case Success(UnlessShutdown.Outcome(_)) => scheduleStallCheck()
        case Failure(ex) =>
          // Never let an unexpected failure end the watchdog, as that would silently disable it.
          logger.warn("Ingestion stall check failed unexpectedly, rescheduling it.", ex)
          scheduleStallCheck()
      }
  }

  /** Restarts the current ingestion instance if it has not made progress for `stallTimeout` while the participant
    * has moved on. An idle ledger (ledger end == last ingested offset) is not a stall.
    *
    * The ledger end query is asynchronous, so the stream may recover or be replaced while it is in flight. We
    * therefore take one snapshot of the progress and the stream instance before asking, derive everything from
    * that snapshot, and claim the stall with a compare-and-set on it: if anything was ingested since, the claim
    * fails and the stream is left alone. Only one check runs at a time.
    */
  private def checkForStall()(implicit tc: TraceContext): Future[Unit] = {
    val stallTimeout = config.ingestion.stallTimeout.underlying
    val snapshot = progress.get()
    val stalledFor = (System.nanoTime() - snapshot.atNanos).nanos
    currentService.filter(_.isActive) match {
      case Some(instance)
          if !isPaused && stalledFor >= stallTimeout &&
            stallCheckInFlight.compareAndSet(false, true) =>
        // `flatMap` so that an exception thrown by `ledgerEnd()` itself ends up in the recover below.
        Future.unit
          .flatMap(_ => connection.ledgerEnd())
          .map { ledgerEnd =>
            if (currentService.exists(_ eq instance) && instance.isActive && !isPaused) {
              val now = System.nanoTime()
              if (
                UpdateIngestionService.hasStalled(
                  stalledFor,
                  stallTimeout,
                  ledgerEnd,
                  snapshot.offset,
                )
              ) {
                // Give the restarted instance a full window. Fails if progress was recorded since the snapshot.
                if (
                  progress.compareAndSet(snapshot, snapshot.copy(atNanos = now, stalled = true))
                ) {
                  logger.warn(
                    s"Ingestion of $ingestionTargetName made no progress for $stalledFor: ingested up to offset " +
                      s"${snapshot.offset} while the ledger end is at $ledgerEnd. Restarting the ingestion loop."
                  )
                  metrics.stallRestarts.inc()(MetricsContext.Empty)
                  instance.initiateShutdown()
                }
              } else {
                // An idle ledger gets a full window before the next check.
                progress.compareAndSet(snapshot, snapshot.copy(atNanos = now)).discard
              }
            }
          }
          .recover { case ex =>
            logger.info(
              "Failed to check ingestion progress against the ledger end, will retry.",
              ex,
            )
          }
          .andThen { case _ => stallCheckInFlight.set(false) }
      case _ => Future.unit
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

  /** What the stall watchdog knows about the ingestion: the offset and time of the last ingested batch, and whether
    * a stall was detected that has not been followed by any ingestion yet.
    */
  private[automation] final case class IngestionProgress(
      offset: Long,
      atNanos: Long,
      stalled: Boolean = false,
  )

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
