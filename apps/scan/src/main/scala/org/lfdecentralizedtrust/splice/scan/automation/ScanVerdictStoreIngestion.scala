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
import org.lfdecentralizedtrust.splice.scan.metrics.ScanMediatorVerdictIngestionMetrics
import org.lfdecentralizedtrust.splice.scan.mediator.MediatorVerdictsClient
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.{KillSwitch, KillSwitches, Materializer}
import org.apache.pekko.stream.scaladsl.{Keep, Source}
import com.digitalasset.canton.util.PekkoUtil
import com.digitalasset.canton.util.PekkoUtil.RetrySourcePolicy
import monocle.Monocle.toAppliedFocusOps
import com.daml.grpc.adapter.ExecutionSequencerFactory

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

import com.digitalasset.canton.mediator.admin.v30

class ScanVerdictStoreIngestion(
    originalContext: TriggerContext,
    config: ScanAppBackendConfig,
    grpcClientMetrics: GrpcClientMetrics,
    store: DbScanVerdictStore,
    migrationId: Long,
    synchronizerId: SynchronizerId,
    ingestionMetrics: ScanMediatorVerdictIngestionMetrics,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    esf: ExecutionSequencerFactory,
    prettyVerdictBatch: Pretty[Seq[v30.Verdict]],
) extends SourceBasedTrigger[Seq[v30.Verdict]]
    with NamedLogging {

  // enforce sequential DB ingestion
  override protected lazy val context: TriggerContext =
    originalContext.focus(_.config.parallelism).replace(1)

  private val mediatorClient =
    new MediatorVerdictsClient(
      config.mediatorAdminClient,
      this,
      grpcClientMetrics,
      context.loggerFactory,
    )(ec, esf)

  override protected def source(implicit tc: TraceContext): Source[Seq[v30.Verdict], NotUsed] = {

    def mediatorClientSource
        : Source[Seq[v30.Verdict], (KillSwitch, scala.concurrent.Future[Done])] = {
      val base: Source[Seq[v30.Verdict], NotUsed] =
        Source
          .future(
            store
              .maxVerdictRecordTime(migrationId)
              .map(_.getOrElse(CantonTimestamp.MinValue))
          )
          .map(ts => Some(ts))
          .flatMapConcat(mediatorClient.streamVerdicts)
          .groupedWithin(
            math.max(1, config.mediatorVerdictIngestion.batchSize),
            config.mediatorVerdictIngestion.batchMaxWait.underlying,
          )

      val withKs = base.viaMat(KillSwitches.single)(Keep.right)
      withKs.watchTermination() { case (ks, done) => (ks: KillSwitch, done) }
    }

    val delay = config.mediatorVerdictIngestion.restartDelay.asFiniteApproximation
    val policy = new RetrySourcePolicy[Unit, Seq[v30.Verdict]] {
      override def shouldRetry(
          lastState: Unit,
          lastEmittedElement: Option[Seq[v30.Verdict]],
          lastFailure: Option[Throwable],
      ): Option[(scala.concurrent.duration.FiniteDuration, Unit)] =
        lastFailure.map(_ => delay -> ())
    }

    PekkoUtil
      .restartSource(
        name = "mediator-verdict-ingestion",
        initial = (),
        mkSource = (_: Unit) => mediatorClientSource,
        policy = policy,
      )
      .map(_.value)
      .mapMaterializedValue(_ => NotUsed)
  }

  override protected def completeTask(batch: Seq[v30.Verdict])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    if (batch.isEmpty) Future.successful(TaskSuccess("empty batch"))
    else {
      val items: Seq[(store.VerdictT, Long => Seq[store.TransactionViewT])] =
        batch.map(toDbRowAndViews)
      store
        .insertVerdictAndTransactionViews(items)
        .transform {
          case Success(_) =>
            val lastRecordTime = batch.lastOption
              .flatMap(v => CantonTimestamp.fromProtoTimestamp(v.getRecordTime).toOption)
              .getOrElse(CantonTimestamp.MinValue)
            ingestionMetrics.lastIngestedRecordTime.updateValue(lastRecordTime.toMicros)
            batch.foreach(_ => ingestionMetrics.verdictCount.mark())
            Success(TaskSuccess(s"inserted ${batch.size} verdicts"))
          case Failure(ex) =>
            ingestionMetrics.errors.mark()
            Failure(ex)
        }
    }
  }

  override protected def isStaleTask(batch: Seq[v30.Verdict])(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

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
  implicit val prettyVerdictBatch: Pretty[Seq[v30.Verdict]] =
    Pretty.prettyOfString(batch => s"verdict_store_ingestion_batch(size=${batch.size})")
}
