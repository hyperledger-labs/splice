// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.automation.{StreamIngestionService, TriggerContext}
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.metrics.StreamIngestionMetrics
import org.lfdecentralizedtrust.splice.scan.sequencer.SequencerTrafficClient
import org.lfdecentralizedtrust.splice.scan.store.db.DbSequencerTrafficSummaryStore
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, LifeCycle, SyncCloseable}
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.sequencer.admin.v30
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.{ExecutionContextExecutor, Future}

class SequencerTrafficSummaryStoreIngestion(
    originalContext: TriggerContext,
    config: ScanAppBackendConfig,
    grpcClientMetrics: GrpcClientMetrics,
    store: DbSequencerTrafficSummaryStore,
    migrationId: Long,
    protected val ingestionMetrics: StreamIngestionMetrics,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    esf: ExecutionSequencerFactory,
    prettyTrafficBatch: Pretty[Seq[v30.ConfirmationRequestTrafficSummary]],
) extends StreamIngestionService[v30.ConfirmationRequestTrafficSummary](originalContext) {

  private val sequencerClient =
    new SequencerTrafficClient(
      config.sequencerAdminClient,
      this,
      grpcClientMetrics,
      context.loggerFactory,
    )(ec, esf)

  // ----- StreamIngestionService implementation -----

  override protected def sourceName: String = "sequencer-traffic-ingestion"

  override protected def batchConfig = StreamIngestionService.BatchConfig(
    restartDelay = config.sequencerTrafficIngestion.restartDelay.asFiniteApproximation,
    batchSize = config.sequencerTrafficIngestion.batchSize,
    batchMaxWait = config.sequencerTrafficIngestion.batchMaxWait.underlying,
  )

  override protected def getMaxTimestamp(implicit tc: TraceContext): Future[CantonTimestamp] =
    store.maxSequencingTime(migrationId).map(_.getOrElse(CantonTimestamp.MinValue))

  override protected def streamFromClient(after: Option[CantonTimestamp])(implicit
      tc: TraceContext
  ): Source[v30.ConfirmationRequestTrafficSummary, NotUsed] =
    sequencerClient.streamTrafficSummaries(after)

  override protected def insertBatch(
      batch: Seq[v30.ConfirmationRequestTrafficSummary]
  )(implicit tc: TraceContext): Future[Unit] = {
    val items: Seq[store.TrafficSummaryT] = batch.map(toDbRow)
    store.insertTrafficSummaries(items)
  }

  override protected def getLastTimestamp(
      batch: Seq[v30.ConfirmationRequestTrafficSummary]
  ): CantonTimestamp =
    batch.lastOption
      .flatMap(s => CantonTimestamp.fromProtoTimestamp(s.getSequencingTime).toOption)
      .getOrElse(CantonTimestamp.MinValue)

  override protected def successMessage(
      batch: Seq[v30.ConfirmationRequestTrafficSummary]
  ): String =
    s"Inserted ${batch.size} traffic summaries. Last ingested sequencing_time is now ${store.lastIngestedSequencingTime}."

  // ----- Conversion -----

  private def toDbRow(summary: v30.ConfirmationRequestTrafficSummary): store.TrafficSummaryT = {
    val envelopes = summary.envelopes.map { env =>
      store.EnvelopeT(
        trafficCost = env.envelopeTrafficCost,
        viewHashes = env.viewHashes,
      )
    }

    store.TrafficSummaryT(
      rowId = 0,
      migrationId = migrationId,
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
