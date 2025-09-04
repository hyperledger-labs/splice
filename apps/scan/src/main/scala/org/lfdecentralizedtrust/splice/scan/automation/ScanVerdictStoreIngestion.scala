// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.scan.metrics.ScanMediatorVerdictIngestionMetrics
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import org.lfdecentralizedtrust.splice.util.HasHealth
import com.digitalasset.canton.topology.SynchronizerId

import scala.concurrent.{ExecutionContextExecutor}

import com.digitalasset.canton.tracing.TraceContext

import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.data.CantonTimestamp
import io.circe.Json
import java.util.concurrent.atomic.AtomicReference
import com.digitalasset.canton.discard.Implicits.*
import scala.concurrent.Future
import scala.concurrent.duration.*
import org.lfdecentralizedtrust.splice.environment.RetryFor

class ScanVerdictStoreIngestion(
    config: ScanAppBackendConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    store: DbScanVerdictStore,
    migrationId: Long,
    synchronizerId: SynchronizerId,
    ingestionMetrics: ScanMediatorVerdictIngestionMetrics,
)(implicit
    ec: ExecutionContextExecutor,
    mat: org.apache.pekko.stream.Materializer,
) extends HasHealth
    with AutoCloseable
    with NamedLogging {

  override def isHealthy: Boolean = true

  private val mediatorClient =
    new org.lfdecentralizedtrust.splice.scan.mediator.MediatorVerdictsClient(
      config.mediatorAdminClient,
      loggerFactory,
    )(ec)

  private val killSwitchRef =
    new AtomicReference[Option[org.apache.pekko.stream.UniqueKillSwitch]](None)

  override def close(): Unit = {
    killSwitchRef.getAndSet(None).foreach(_.shutdown())
    com.digitalasset.canton.lifecycle.LifeCycle.close(mediatorClient)(logger)
  }

  private def startStream()(implicit tc: TraceContext): Unit = {
    import org.apache.pekko.stream.scaladsl.{Keep, Sink}
    import org.apache.pekko.stream.KillSwitches

    // Determine ingestion resume point: if there are no rows for current
    // migration, start from MinValue
    val resumeF = store
      .maxVerdictRecordTime(migrationId)
      .map(_.getOrElse(CantonTimestamp.MinValue))

    resumeF.foreach { resumeTs =>
      val materialized: (
          org.apache.pekko.stream.UniqueKillSwitch,
          scala.concurrent.Future[org.apache.pekko.Done],
      ) = mediatorClient
        .streamVerdicts(Some(resumeTs))
        .viaMat(KillSwitches.single)(Keep.right)
        .groupedWithin(
          math.max(1, config.mediatorVerdictIngestion.batchSize),
          1.second, // Perform ingestion after this delay
        )
        .mapAsync(1) { batch =>
          ingestBatchWithRetry(batch)(tc)
        }
        .toMat(Sink.ignore)(Keep.both)
        .run()
      val (killSwitch, done) = materialized
      killSwitchRef.set(Some(killSwitch))
      done.failed.foreach { t =>
        logger.warn("Mediator verdict stream failed; scheduling restart", t)
        ingestionMetrics.errors.mark()
        retryProvider
          .scheduleAfterUnlessShutdown(
            action = scala.concurrent.Future {
              startStream()(TraceContext.createNew())
              ()
            },
            clock = clock,
            delay = config.mediatorVerdictIngestion.restartDelay,
            jitter = 0.2,
          )(ec)
          .discard
      }(ec)
    }
  }

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
        val confirmingPartiesJson: Json = io.circe.Json.fromValues(
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
        )
      }.toSeq
    }
    (row, mkViews)
  }

  private def ingestBatchWithRetry(
      batch: Seq[v30.Verdict]
  )(implicit tc: TraceContext): Future[Unit] = {
    if (batch.isEmpty) Future.unit
    else {
      val firstUpdateId = batch.headOption.map(_.updateId).getOrElse("")
      val opId = s"scan_verdict_batch_ingest"
      val desc = s"insert ${batch.size} mediator verdict(s) starting at update_id=$firstUpdateId"
      retryProvider.retry(
        RetryFor.LongRunningAutomation,
        operationId = opId,
        operationDescription = desc,
        task = {
          val items: Seq[(store.VerdictT, Long => Seq[store.TransactionViewT])] =
            batch.map(toDbRowAndViews)
          store.insertVerdictAndTransactionViews(items).map { _ =>
            // Update metrics: last record time and count
            val lastRecordTime = batch.lastOption
              .flatMap(v => CantonTimestamp.fromProtoTimestamp(v.getRecordTime).toOption)
              .getOrElse(CantonTimestamp.MinValue)
            ingestionMetrics.lastIngestedRecordTime.updateValue(lastRecordTime.toMicros)
            batch.foreach(_ => ingestionMetrics.verdictCount.mark())
          }
        },
        logger = logger,
      )
    }
  }

  startStream()(TraceContext.createNew())
}
