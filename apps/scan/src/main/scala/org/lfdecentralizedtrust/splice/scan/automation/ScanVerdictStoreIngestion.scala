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
import org.lfdecentralizedtrust.splice.scan.sequencer.SequencerTrafficClient
import org.lfdecentralizedtrust.splice.scan.store.db.{DbScanVerdictStore}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
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

import org.lfdecentralizedtrust.splice.scan.rewards.AppActivityComputation

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
    sequencerTrafficClientO: Option[SequencerTrafficClient],
    appActivityComputation: AppActivityComputation,
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
            store.waitUntilInitialized.flatMap(_ =>
              store
                .maxVerdictRecordTime(migrationId)
                .map(_.getOrElse(CantonTimestamp.MinValue))
            )
          )
          .map { ts =>
            logger.info(s"Streaming verdicts starting from $ts")
            Some(ts)
          }
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
      ): Option[(scala.concurrent.duration.FiniteDuration, Unit)] = {
        val prefixMsg =
          s"RetrySourcePolicy for restart of mediatorClientSource with ${delay} delay:"
        lastFailure match {
          case Some(t) =>
            ingestionMetrics.restartErrors.mark()
            logger.info(s"$prefixMsg Last failure: ${ErrorUtil.messageWithStacktrace(t)}")
          case None =>
            logger.debug(s"$prefixMsg No failure, normal restart.")
        }
        // always restart, even if the connection was closed normally (eg after a mediator restart)
        Some(delay -> ())
      }
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
      // Extract sequencing times and build view_hash -> view_id correlation map
      val (sequencingTimes, viewHashToViewIdByTime) =
        DbScanVerdictStore.buildViewHashCorrelation(batch)

      // 1. Fetch traffic summaries FIRST (before any DB operations)
      val trafficSummariesF: Future[Seq[DbScanVerdictStore.TrafficSummaryT]] =
        sequencerTrafficClientO match {
          case Some(sequencerTrafficClient) =>
            sequencerTrafficClient
              .getTrafficSummaries(sequencingTimes)
              .map(_.map { proto =>
                DbScanVerdictStore
                  .fromProtoWithCorrelation(proto, viewHashToViewIdByTime, logger)
              })
          case _ =>
            Future.successful(Seq.empty)
        }

      // 2. Insert verdicts, traffic summaries, and app activity records in a single transaction
      val result = for {
        trafficSummaries <- trafficSummariesF

        // Pair traffic summaries with verdicts by sequencing time
        summaryByTime = trafficSummaries.map(s => s.sequencingTime -> s).toMap
        items = batch.map(v =>
          DbScanVerdictStore.fromProto(v, migrationId, synchronizerId, summaryByTime)
        )

        // TODO(#4060): log an error and fail ingestion if a trafficSummary is missing for a verdict
        //
        // Once #4060 is confirmed, this should simplify, as 'items' will fail
        // construction if any verdicts did not have a trafficSummary
        summariesWithVerdicts = batch.flatMap { v =>
          CantonTimestamp.fromProtoTimestamp(v.getRecordTime).toOption.flatMap { recordTime =>
            summaryByTime.get(recordTime).map(_ -> v)
          }
        }

        // Compute app activity records (pure computation, before transaction)
        appActivityRecords = appActivityComputation.computeActivities(
          summariesWithVerdicts,
          Set.empty[PartyId], // featuredAppProviders
        )

        _ <- store.insertVerdictsWithAppActivityRecords(items, appActivityRecords)
      } yield (trafficSummaries.size, appActivityRecords.size)

      result.transform {
        case Success((trafficSummaryCount, appActivityCount)) =>
          val lastRecordTime = batch.lastOption
            .flatMap(v => CantonTimestamp.fromProtoTimestamp(v.getRecordTime).toOption)
            .getOrElse(CantonTimestamp.MinValue)
          ingestionMetrics.lastIngestedRecordTime.updateValue(lastRecordTime)
          ingestionMetrics.verdictCount.mark(batch.size.toLong)(MetricsContext.Empty)
          Success(
            TaskSuccess(
              s"Inserted ${batch.size} verdicts, $trafficSummaryCount traffic summaries, $appActivityCount app activity records. " +
                s"Last ingested verdict record_time is now ${store.lastIngestedRecordTime}. " +
                s"Inserted verdicts: ${batch.map(_.updateId)}"
            )
          )
        case Failure(ex) =>
          ingestionMetrics.errors.mark()
          Failure(ex)
        // TODO(#2856): just failing here may result in skipping ingestion.
        // This must be fixed as otherwise we loose determinism!
        // Fix this to ensure ingestion always works reliably.
      }
    }
  }

  override protected def isStaleTask(batch: Seq[v30.Verdict])(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    val baseCloseables = super.closeAsync() :+
      SyncCloseable("MediatorVerdictsClient", LifeCycle.close(mediatorClient)(logger))

    sequencerTrafficClientO match {
      case Some(sequencerTrafficClient) =>
        baseCloseables :+ SyncCloseable(
          "SequencerTrafficClient",
          LifeCycle.close(sequencerTrafficClient)(logger),
        )
      case None =>
        baseCloseables
    }
  }
}

object ScanVerdictStoreIngestion {
  // Batches are small enough that we can log the update ids for debuggability
  implicit val prettyVerdictBatch: Pretty[Seq[v30.Verdict]] =
    Pretty.prettyOfString(batch => s"verdict_store_ingestion_batch(${batch.map(_.updateId)})")
}
