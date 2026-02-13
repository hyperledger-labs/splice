// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.MetricHandle.{Gauge, Meter}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{ErrorUtil, PekkoUtil}
import com.digitalasset.canton.util.PekkoUtil.RetrySourcePolicy
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.{KillSwitch, KillSwitches, Materializer}
import org.apache.pekko.stream.scaladsl.{Keep, Source}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/** Abstract base class for gRPC stream-based ingestion into a database store.
  *
  * Provides common structure for:
  *   - Sequential DB ingestion (parallelism=1)
  *   - Streaming from a gRPC client with batching
  *   - Retry policy with restart on failure
  *   - Metrics for tracking ingestion progress and errors
  *
  * @tparam T The proto message type being ingested (e.g., Verdict, ConfirmationRequestTrafficSummary)
  */
abstract class StreamIngestionService[T](
    originalContext: TriggerContext
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    pretty: Pretty[Seq[T]],
) extends SourceBasedTrigger[Seq[T]]
    with NamedLogging {

  // enforce sequential DB ingestion
  override protected lazy val context: TriggerContext =
    originalContext.focus(_.config.parallelism).replace(1)

  // ----- Abstract members to be implemented by subclasses -----

  /** Name for logging and PekkoUtil.restartSource */
  protected def sourceName: String

  /** Batching and restart configuration */
  protected def batchConfig: StreamIngestionService.BatchConfig

  /** Get the max timestamp to resume streaming from */
  protected def getMaxTimestamp(implicit tc: TraceContext): Future[CantonTimestamp]

  /** Create the stream from the gRPC client */
  protected def streamFromClient(after: Option[CantonTimestamp])(implicit
      tc: TraceContext
  ): Source[T, NotUsed]

  /** Insert a batch of items into the store */
  protected def insertBatch(batch: Seq[T])(implicit tc: TraceContext): Future[Unit]

  /** Extract the timestamp from the last item in a batch for metrics */
  protected def getLastTimestamp(batch: Seq[T]): CantonTimestamp

  /** Generate success message for logging */
  protected def successMessage(batch: Seq[T]): String

  /** Metrics for this ingestion service */
  protected def ingestionMetrics: StreamIngestionService.IngestionMetrics

  // ----- Implementation -----

  override protected def source(implicit tc: TraceContext): Source[Seq[T], NotUsed] = {

    def clientSource: Source[Seq[T], (KillSwitch, Future[Done])] = {
      val base: Source[Seq[T], NotUsed] =
        Source
          .future(getMaxTimestamp)
          .map { ts =>
            logger.info(s"Streaming $sourceName starting from $ts")
            Some(ts)
          }
          .flatMapConcat(streamFromClient)
          .groupedWithin(math.max(1, batchConfig.batchSize), batchConfig.batchMaxWait)

      val withKs = base.viaMat(KillSwitches.single)(Keep.right)
      withKs.watchTermination() { case (ks, done) => (ks: KillSwitch, done) }
    }

    val policy = new RetrySourcePolicy[Unit, Seq[T]] {
      override def shouldRetry(
          lastState: Unit,
          lastEmittedElement: Option[Seq[T]],
          lastFailure: Option[Throwable],
      ): Option[(FiniteDuration, Unit)] = {
        val prefixMsg =
          s"RetrySourcePolicy for restart of $sourceName with ${batchConfig.restartDelay} delay:"
        lastFailure match {
          case Some(t) =>
            ingestionMetrics.restartErrors.mark()
            logger.info(s"$prefixMsg Last failure: ${ErrorUtil.messageWithStacktrace(t)}")
          case None =>
            logger.debug(s"$prefixMsg No failure, normal restart.")
        }
        // always restart, even if the connection was closed normally (eg after server restart)
        Some(batchConfig.restartDelay -> ())
      }
    }

    PekkoUtil
      .restartSource(
        name = sourceName,
        initial = (),
        mkSource = (_: Unit) => clientSource,
        policy = policy,
      )
      .map(_.value)
      .mapMaterializedValue(_ => NotUsed)
  }

  override protected def completeTask(batch: Seq[T])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    if (batch.isEmpty) Future.successful(TaskSuccess("empty batch"))
    else {
      insertBatch(batch).transform {
        case Success(_) =>
          val lastTs = getLastTimestamp(batch)
          ingestionMetrics.lastIngestedTimestamp.updateValue(lastTs)
          ingestionMetrics.count.mark(batch.size.toLong)(MetricsContext.Empty)
          Success(TaskSuccess(successMessage(batch)))
        case Failure(ex) =>
          ingestionMetrics.errors.mark()
          Failure(ex)
        // TODO(#2856): just failing here may result in skipping ingestion.
        // This must be fixed as otherwise we loose determinism! Fix this to ensure ingestion always works realiably.
      }
    }
  }

  override protected def isStaleTask(batch: Seq[T])(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)
}

object StreamIngestionService {

  /** Configuration for batching and restart behavior */
  case class BatchConfig(
      restartDelay: FiniteDuration,
      batchSize: Int,
      batchMaxWait: FiniteDuration,
  )

  /** Trait for ingestion metrics. Implementations provide the actual metric instances. */
  trait IngestionMetrics {
    def lastIngestedTimestamp: Gauge[CantonTimestamp]
    def count: Meter
    def errors: Meter
    def restartErrors: Meter
  }
}
