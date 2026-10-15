// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.pattern.after
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.lfdecentralizedtrust.splice.{PekkoRetryingService, PekkoRetryableService}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.BulkStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

trait UpdateHistoryBulkStorageWriter {

  def getNextSegmentAfter(
      afterO: Option[UpdatesSegment]
  )(implicit tc: TraceContext): Future[Option[UpdatesSegment]]

  /** The main Flow that processes a given segment of updates.
    * The Flow must emit back the same segment as its output once processing is complete.
    */
  def processSegmentsFlow(implicit
      tc: TraceContext
  ): Flow[UpdatesSegment, UpdatesSegment, NotUsed]
}

class UpdateHistoryBulkStoragePersistentProgress(
    kvStoreKey: String,
    kvProvider: ScanKeyValueProvider,
    metric: MetricHandle.Gauge[CantonTimestamp],
    override val loggerFactory: NamedLoggerFactory,
) extends NamedLogging
    with Spanning {

  import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider.updatesSegmentCodec

  def readLatestProcessedSegment(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[UpdatesSegment]] = {
    kvProvider.store.readValueAndLogOnDecodingFailure(kvStoreKey).value
  }

  def persistLatestProcessedSegment(segment: UpdatesSegment)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = {
    metric.updateValue(segment.toTimestamp)
    kvProvider.store
      .setValue(kvStoreKey, segment)
      .map(_ => {
        logger.info(
          s"Successfully completed processing updates segment $segment, persisted as the latest processed segment in bulk storage"
        )
      })
  }

  def reset(implicit tc: TraceContext): Future[Unit] = {
    kvProvider.store.deleteKey(kvStoreKey)
  }
}

/** An abstract class for pipelines that process update history for bulk storage.
  */
class UpdateHistoryBulkStorage(
    description: String,
    writer: UpdateHistoryBulkStorageWriter,
    val persistentProgress: UpdateHistoryBulkStoragePersistentProgress,
    appConfig: BulkStorageConfig,
    mayStart: Source[Boolean, Cancellable],
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning
    with PekkoRetryableService[UpdatesSegment] {

  private def getUpdatesSegmentsAfter(
      afterO: Option[UpdatesSegment]
  )(implicit tc: TraceContext): Source[UpdatesSegment, NotUsed] = {
    Source
      .unfoldAsync(afterO) { last =>
        writer.getNextSegmentAfter(last).flatMap {
          case Some(segment: UpdatesSegment) =>
            logger.info(
              s"Next updates segment available: $segment"
            )
            Future.successful(
              Some(
                (
                  Some(segment),
                  Some(segment),
                )
              )
            )
          case None =>
            logger.info(
              s"No new updates segment available, sleeping..."
            )
            // Wait for the next segment to become available
            after(
              appConfig.updatesPollingInterval.underlying,
              actorSystem.scheduler,
            )(
              Future.successful(
                Some(
                  (
                    last,
                    None,
                  )
                )
              )
            )
        }
      }
//      .collect { case Some(segment) => segment }
      .mapConcat(_.toList)
  }

  private def mksrc()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Source[UpdatesSegment, Cancellable] = {

    mayStart.flatMap { _ =>
      Source
        .future(persistentProgress.readLatestProcessedSegment)
        .flatMapConcat(getUpdatesSegmentsAfter(_))
        .via(writer.processSegmentsFlow)
        .mapAsync(1) { segment =>
          persistentProgress.persistLatestProcessedSegment(segment).map(_ => segment)
        }
    }
  }

  override def asPekkoRetryingService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[UpdatesSegment] = {
    withNewTrace(description) { implicit traceContext => _ =>
      val src = mksrc()
      new PekkoRetryingService(
        src,
        Sink.ignore,
        automationConfig,
        backoffClock,
        description,
        retryProvider,
        loggerFactory,
      )
    }
  }

}

object UpdateHistoryBulkStorage {
  case class UpdateHistoryObjectsResponse(
      objects: Seq[ObjectKeyAndChecksum],
      nextPageTokenO: Option[String],
  )
}
