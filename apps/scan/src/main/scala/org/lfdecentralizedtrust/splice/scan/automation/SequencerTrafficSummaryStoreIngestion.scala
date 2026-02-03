// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.automation.{
  SourceBasedTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.metrics.ScanSequencerTrafficIngestionMetrics
import org.lfdecentralizedtrust.splice.scan.sequencer.SequencerTrafficClient
import org.lfdecentralizedtrust.splice.scan.store.db.DbSequencerTrafficSummaryStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.{KillSwitch, KillSwitches, Materializer}
import org.apache.pekko.stream.scaladsl.{Keep, Source}
import com.digitalasset.canton.util.{ErrorUtil, PekkoUtil}
import com.digitalasset.canton.util.PekkoUtil.RetrySourcePolicy
import monocle.Monocle.toAppliedFocusOps
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.sequencer.admin.v30

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

class SequencerTrafficSummaryStoreIngestion(
    originalContext: TriggerContext,
    config: ScanAppBackendConfig,
    grpcClientMetrics: GrpcClientMetrics,
    store: DbSequencerTrafficSummaryStore,
    migrationId: Long,
    synchronizerId: SynchronizerId,
    ingestionMetrics: ScanSequencerTrafficIngestionMetrics,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    esf: ExecutionSequencerFactory,
    prettyTrafficBatch: Pretty[Seq[v30.ConfirmationRequestTrafficSummary]],
) extends SourceBasedTrigger[Seq[v30.ConfirmationRequestTrafficSummary]]
    with NamedLogging {

  // enforce sequential DB ingestion
  override protected lazy val context: TriggerContext =
    originalContext.focus(_.config.parallelism).replace(1)

  private val sequencerClient =
    new SequencerTrafficClient(
      config.sequencerAdminClient,
      this,
      grpcClientMetrics,
      context.loggerFactory,
    )(ec, esf)

  override protected def source(implicit
      tc: TraceContext
  ): Source[Seq[v30.ConfirmationRequestTrafficSummary], NotUsed] = {

    def sequencerClientSource
        : Source[Seq[v30.ConfirmationRequestTrafficSummary], (KillSwitch, Future[Done])] = {
      val base: Source[Seq[v30.ConfirmationRequestTrafficSummary], NotUsed] =
        Source
          .future(
            store.waitUntilInitialized.flatMap(_ =>
              store
                .maxSequencingTime(migrationId)
                .map(_.getOrElse(CantonTimestamp.MinValue))
            )
          )
          .map { ts =>
            logger.info(s"Streaming traffic summaries starting from $ts")
            Some(ts)
          }
          .flatMapConcat(sequencerClient.streamTrafficSummaries)
          .groupedWithin(
            math.max(1, config.sequencerTrafficIngestion.batchSize),
            config.sequencerTrafficIngestion.batchMaxWait.underlying,
          )

      val withKs = base.viaMat(KillSwitches.single)(Keep.right)
      withKs.watchTermination() { case (ks, done) => (ks: KillSwitch, done) }
    }

    val delay = config.sequencerTrafficIngestion.restartDelay.asFiniteApproximation
    val policy = new RetrySourcePolicy[Unit, Seq[v30.ConfirmationRequestTrafficSummary]] {
      override def shouldRetry(
          lastState: Unit,
          lastEmittedElement: Option[Seq[v30.ConfirmationRequestTrafficSummary]],
          lastFailure: Option[Throwable],
      ): Option[(scala.concurrent.duration.FiniteDuration, Unit)] = {
        val prefixMsg =
          s"RetrySourcePolicy for restart of sequencerClientSource with ${delay} delay:"
        lastFailure match {
          case Some(t) =>
            ingestionMetrics.restartErrors.mark()
            logger.info(s"$prefixMsg Last failure: ${ErrorUtil.messageWithStacktrace(t)}")
          case None =>
            logger.debug(s"$prefixMsg No failure, normal restart.")
        }
        // always restart, even if the connection was closed normally (eg after a sequencer restart)
        Some(delay -> ())
      }
    }

    PekkoUtil
      .restartSource(
        name = "sequencer-traffic-ingestion",
        initial = (),
        mkSource = (_: Unit) => sequencerClientSource,
        policy = policy,
      )
      .map(_.value)
      .mapMaterializedValue(_ => NotUsed)
  }

  override protected def completeTask(batch: Seq[v30.ConfirmationRequestTrafficSummary])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    if (batch.isEmpty) Future.successful(TaskSuccess("empty batch"))
    else {
      val items: Seq[store.TrafficSummaryT] = batch.map(toDbRow)
      store
        .insertTrafficSummaries(items)
        .transform {
          case Success(_) =>
            val lastSequencingTime = batch.lastOption
              .flatMap(s => CantonTimestamp.fromProtoTimestamp(s.getSequencingTime).toOption)
              .getOrElse(CantonTimestamp.MinValue)
            ingestionMetrics.lastIngestedSequencingTime.updateValue(lastSequencingTime)
            ingestionMetrics.summaryCount.mark(batch.size.toLong)(MetricsContext.Empty)
            Success(
              TaskSuccess(
                s"Inserted ${batch.size} traffic summaries. Last ingested sequencing_time is now ${store.lastIngestedSequencingTime}."
              )
            )
          case Failure(ex) =>
            ingestionMetrics.errors.mark()
            Failure(ex)
        }
    }
  }

  override protected def isStaleTask(batch: Seq[v30.ConfirmationRequestTrafficSummary])(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  private def toDbRow(
      summary: v30.ConfirmationRequestTrafficSummary
  ): store.TrafficSummaryT = {
    val envelopes = summary.envelopes.map { env =>
      store.EnvelopeT(
        trafficCost = env.envelopeTrafficCost,
        viewHashes = env.viewHashes,
      )
    }

    store.TrafficSummaryT(
      rowId = 0,
      migrationId = migrationId,
      domainId = synchronizerId,
      sequencingTime = CantonTimestamp
        .fromProtoTimestamp(summary.getSequencingTime)
        .getOrElse(throw new IllegalArgumentException("Invalid sequencing timestamp")),
      sender = summary.sender,
      totalTrafficCost = summary.totalTrafficCost,
      envelopes = envelopes,
    )
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+ SyncCloseable(
      "SequencerTrafficClient",
      LifeCycle.close(sequencerClient)(logger),
    )
}

object SequencerTrafficSummaryStoreIngestion {
  implicit val prettyTrafficBatch: Pretty[Seq[v30.ConfirmationRequestTrafficSummary]] =
    Pretty.prettyOfString(batch =>
      s"traffic_store_ingestion_batch(size=${batch.size}, senders=${batch.map(_.sender).distinct.mkString(",")})"
    )
}
