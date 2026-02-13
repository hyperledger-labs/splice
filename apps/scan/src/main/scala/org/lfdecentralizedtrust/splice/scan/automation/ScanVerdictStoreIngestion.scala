// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.automation.{StreamIngestionService, TriggerContext}
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.metrics.StreamIngestionMetrics
import org.lfdecentralizedtrust.splice.scan.mediator.MediatorVerdictsClient
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, LifeCycle, SyncCloseable}
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.{ExecutionContextExecutor, Future}

class ScanVerdictStoreIngestion(
    originalContext: TriggerContext,
    config: ScanAppBackendConfig,
    grpcClientMetrics: GrpcClientMetrics,
    store: DbScanVerdictStore,
    migrationId: Long,
    synchronizerId: SynchronizerId,
    protected val ingestionMetrics: StreamIngestionMetrics,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    esf: ExecutionSequencerFactory,
    prettyVerdictBatch: Pretty[Seq[v30.Verdict]],
) extends StreamIngestionService[v30.Verdict](originalContext) {

  private val mediatorClient =
    new MediatorVerdictsClient(
      config.mediatorAdminClient,
      this,
      grpcClientMetrics,
      context.loggerFactory,
    )(ec, esf)

  // ----- StreamIngestionService implementation -----

  override protected def sourceName: String = "mediator-verdict-ingestion"

  override protected def batchConfig = StreamIngestionService.BatchConfig(
    restartDelay = config.mediatorVerdictIngestion.restartDelay.asFiniteApproximation,
    batchSize = config.mediatorVerdictIngestion.batchSize,
    batchMaxWait = config.mediatorVerdictIngestion.batchMaxWait.underlying,
  )

  override protected def getMaxTimestamp(implicit tc: TraceContext): Future[CantonTimestamp] =
    store.waitUntilInitialized.flatMap(_ =>
      store.maxVerdictRecordTime(migrationId).map(_.getOrElse(CantonTimestamp.MinValue))
    )

  override protected def streamFromClient(after: Option[CantonTimestamp])(implicit
      tc: TraceContext
  ): Source[v30.Verdict, NotUsed] =
    mediatorClient.streamVerdicts(after)

  override protected def insertBatch(batch: Seq[v30.Verdict])(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val items: Seq[(store.VerdictT, Long => Seq[store.TransactionViewT])] =
      batch.map(toDbRowAndViews)
    store.insertVerdictAndTransactionViews(items)
  }

  override protected def getLastTimestamp(batch: Seq[v30.Verdict]): CantonTimestamp =
    batch.lastOption
      .flatMap(v => CantonTimestamp.fromProtoTimestamp(v.getRecordTime).toOption)
      .getOrElse(CantonTimestamp.MinValue)

  override protected def successMessage(batch: Seq[v30.Verdict]): String =
    s"Inserted ${batch.size} verdicts. Last ingested verdict record_time is now ${store.lastIngestedRecordTime}. Inserted verdicts: ${batch
        .map(_.updateId)}"

  // ----- Conversion -----

  private def toDbRowAndViews(
      verdict: v30.Verdict
  ): (store.VerdictT, Long => Seq[store.TransactionViewT]) = {
    val transactionRootViews = verdict.getTransactionViews.rootViews
    val resultShort: Short = DbScanVerdictStore.VerdictResultDbValue.fromProto(verdict.verdict)
    val row = new store.VerdictT(
      rowId = 0,
      migrationId = migrationId,
      domainId = synchronizerId,
      recordTime = CantonTimestamp
        .fromProtoTimestamp(verdict.getRecordTime)
        .getOrElse(throw new IllegalArgumentException("Invalid timestamp")),
      finalizationTime = CantonTimestamp
        .fromProtoTimestamp(verdict.getFinalizationTime)
        .getOrElse(throw new IllegalArgumentException("Invalid timestamp")),
      submittingParticipantUid = verdict.submittingParticipantUid,
      verdictResult = resultShort,
      mediatorGroup = verdict.mediatorGroup,
      updateId = verdict.updateId,
      submittingParties = verdict.submittingParties,
      transactionRootViews = transactionRootViews,
    )

    val mkViews: Long => Seq[store.TransactionViewT] = { rowId =>
      verdict.getTransactionViews.views.map { case (viewId, txView) =>
        val confirmingPartiesJson: Json = Json.fromValues(
          txView.confirmingParties.map { q =>
            Json.obj(
              "parties" -> Json.fromValues(q.parties.map(Json.fromString)),
              "threshold" -> Json.fromInt(q.threshold),
            )
          }
        )
        new store.TransactionViewT(
          verdictRowId = rowId,
          viewId = viewId,
          informees = txView.informees,
          confirmingParties = confirmingPartiesJson,
          subViews = txView.subViews,
          viewHash = Some(txView.viewHash).filter(_.nonEmpty),
        )
      }.toSeq
    }
    (row, mkViews)
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+ SyncCloseable(
      "MediatorVerdictsClient",
      LifeCycle.close(mediatorClient)(logger),
    )
}

object ScanVerdictStoreIngestion {
  // Batches are small enough that we can log the update ids for debuggability
  implicit val prettyVerdictBatch: Pretty[Seq[v30.Verdict]] =
    Pretty.prettyOfString(batch => s"verdict_store_ingestion_batch(${batch.map(_.updateId)})")
}
