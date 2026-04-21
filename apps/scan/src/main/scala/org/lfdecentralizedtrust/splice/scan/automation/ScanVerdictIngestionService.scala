// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.grpc.GrpcException
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.metrics.api.MetricsContext
import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.sequencing.traffic.TrafficControlErrors
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.protobuf.StatusProto
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.automation.{RetryingService, ServiceWithShutdown}
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, ServiceWithGuaranteedShutdown}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.mediator.MediatorVerdictsClient
import org.lfdecentralizedtrust.splice.scan.metrics.ScanMediatorVerdictIngestionMetrics
import org.lfdecentralizedtrust.splice.scan.rewards.AppActivityComputation
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.scan.ScanSynchronizerNode
import org.lfdecentralizedtrust.splice.scan.sequencer.SequencerTrafficClient

import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

/** Ingestion service for the verdict store.
  *
  * Streams verdicts from the current mediator and, if the mediator returns a LSU complete on the stream, continues from the successor.
  * It also checks the last ingestion compared to the LSU upgrade time to determine whether to start streaming from the current or successor mediator.
  */
class ScanVerdictIngestionService(
    config: ScanAppBackendConfig,
    synchronizerNodes: LocalSynchronizerNodes[ScanSynchronizerNode],
    grpcClientMetrics: GrpcClientMetrics,
    store: DbScanVerdictStore,
    migrationId: Long,
    synchronizerId: SynchronizerId,
    ingestionMetrics: ScanMediatorVerdictIngestionMetrics,
    appActivityComputationO: Option[AppActivityComputation],
    backoffClock: Clock,
    override protected val retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    esf: ExecutionSequencerFactory,
) extends RetryingService(config.automation, backoffClock, "verdict ingestion") {

  private lazy val currentMediatorClient =
    new MediatorVerdictsClient(
      config.synchronizerNodes.current.mediator,
      this,
      grpcClientMetrics,
      loggerFactory,
    )(ec, esf)

  private lazy val successorMediatorClientO =
    config.synchronizerNodes.successor.map { successorConfig =>
      new MediatorVerdictsClient(
        successorConfig.mediator,
        this,
        grpcClientMetrics,
        loggerFactory,
      )(ec, esf)
    }

  /** Completes when all dependencies are ready to serve data. */
  private def waitForStores(): Future[Unit] =
    for {
      _ <- store.waitUntilInitialized
      _ <- appActivityComputationO match {
        case Some(appActivityComputation) => appActivityComputation.waitUntilInitialized
        case None => Future.unit
      }
    } yield ()

  /** When starting a fresh stream, the record time from which to start streaming */
  private def getIngestionStart()(implicit tc: TraceContext) = {
    store.maxVerdictRecordTime(migrationId).map(_.getOrElse(CantonTimestamp.MinValue))
  }

  override protected def instantiateService()(implicit
      traceContext: TraceContext
  ): Future[ServiceWithShutdown] =
    for {
      _ <- waitForStores()
      ingestionStart <- getIngestionStart()
    } yield {
      logger.info(s"Streaming verdicts starting from $ingestionStart")
      val currentSource =
        streamVerdictsAndBatchWithTraffic(
          ingestionStart,
          currentMediatorClient,
          synchronizerNodes.current.sequencerTrafficClient,
        )
      val completedWithCompleteF = Promise[Option[v30.VerdictsResponse.Complete]]()
      val source = currentSource
        .mapMaterializedValue { completeFuture =>
          completeFuture.foreach { result =>
            completedWithCompleteF.trySuccess(result).discard
          }(ec)
          completeFuture.failed.foreach { ex =>
            completedWithCompleteF.tryFailure(ex)
          }(ec)
          NotUsed
        }
        .concat(
          Source
            .futureSource(
              completedWithCompleteF.future.flatMap {
                case Some(_) =>
                  getIngestionStart().map { successorIngestionStart =>
                    successorMediatorClientO match {
                      case Some(successorMediatorClient) =>
                        logger.info(
                          s"Continuing verdict ingestion with successor mediator client from $successorIngestionStart"
                        )
                        streamVerdictsAndBatchWithTraffic(
                          successorIngestionStart,
                          successorMediatorClient,
                          synchronizerNodes.successor.flatMap(_.sequencerTrafficClient),
                        )
                          .mapMaterializedValue(_ => NotUsed)
                      case None =>
                        logger.error(
                          "Current mediator verdicts stream completed but no successor mediator client is configured"
                        )
                        Source.empty
                    }
                  }
                case None =>
                  Future.successful(Source.empty)
              }
            )
            .mapMaterializedValue(_ => NotUsed)
        )
      new ServiceWithGuaranteedShutdown(
        source = source,
        map = processWhenUnpaused,
        retryProvider = retryProvider,
        loggerFactory = loggerFactory.append("subsClient", this.getClass.getSimpleName),
      )
    }

  private def streamVerdictsAndBatchWithTraffic(
      ingestionStart: CantonTimestamp,
      mediatorClient: MediatorVerdictsClient,
      sequencerTrafficClient: Option[SequencerTrafficClient],
  )(implicit tc: TraceContext) = {
    batchSource(
      mediatorClient
        .streamVerdicts(Some(ingestionStart))
    ).mapAsync(1) { batch =>
      // Extract sequencing times and build view_hash -> view_id correlation map
      val (sequencingTimes, viewHashToViewIdByTime) = buildViewHashCorrelation(batch)

      // 1. Fetch traffic summaries FIRST (before any DB operations)
      val trafficSummariesF: Future[Seq[DbScanVerdictStore.TrafficSummaryT]] =
        sequencerTrafficClient match {
          case Some(sequencerTrafficClient) =>
            getTrafficSummaries(
              sequencerTrafficClient,
              sequencingTimes,
              viewHashToViewIdByTime,
            )
          case None =>
            Future.successful(Seq.empty)
        }

      trafficSummariesF.map(trafficSummaries => (batch, trafficSummaries))
    }
  }

  private def process(input: (Seq[v30.Verdict], Seq[DbScanVerdictStore.TrafficSummaryT]))(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val (verdicts, trafficSummary) = input
    if (verdicts.isEmpty) {
      logger.error(
        "Received empty batch of verdicts to ingest. This is never supposed to happen."
      )
      Future.successful(())
    } else {

      // Pair traffic summaries with verdicts by sequencing time
      val summaryByTime = trafficSummary.map(s => s.sequencingTime -> s).toMap
      val items =
        verdicts.map(v =>
          DbScanVerdictStore.fromProto(v, migrationId, synchronizerId, summaryByTime)
        )

      // TODO(#4060): log an error and fail ingestion if a trafficSummary is missing for a verdict
      //
      // Once #4060 is confirmed, this should simplify, as 'items' will fail
      // construction if any verdicts did not have a trafficSummary
      val summariesWithVerdicts = verdicts.flatMap { v =>
        val recordTime = CantonTimestamp.tryFromProtoTimestamp(v.getRecordTime)
        summaryByTime.get(recordTime).map(_ -> v)
      }
      // Insert verdicts, traffic summaries, and app activity records in a single transaction
      for {

        // Compute app activity records (before DB transaction).
        // Records have verdictRowId = DUMMY_VERDICT_ROW_ID
        // the store resolves actual row_ids during insertion.
        appActivityRecords <- appActivityComputationO match {
          case Some(appActivityComputation) =>
            appActivityComputation.computeActivities(summariesWithVerdicts).map {
              _.flatMap { case (summary, _, recordO) =>
                recordO.map(summary.sequencingTime -> _)
              }
            }
          case None => Future.successful(Seq.empty)
        }

        _ <- store.insertVerdictsWithAppActivityRecords(items, appActivityRecords)
      } yield {
        val lastRecordTime = verdicts.lastOption
          .flatMap(v => CantonTimestamp.fromProtoTimestamp(v.getRecordTime).toOption)
          .getOrElse(CantonTimestamp.MinValue)
        ingestionMetrics.lastIngestedRecordTime.updateValue(lastRecordTime)
        ingestionMetrics.verdictCount.mark(verdicts.size.toLong)(MetricsContext.Empty)
        ingestionMetrics.batchSize.update(verdicts.size.toLong)(MetricsContext.Empty)
        logger.info(
          s"Inserted ${verdicts.size} verdicts, ${trafficSummary.size} traffic summaries, " +
            s"${appActivityRecords.size} app activity records. " +
            s"Last ingested verdict record_time is now ${store.lastIngestedRecordTime}. " +
            s"Inserted verdicts: ${verdicts.map(_.updateId)}"
        )
      }
    }
  }

  private def getTrafficSummaries(
      client: SequencerTrafficClient,
      sequencingTimes: Seq[CantonTimestamp],
      viewHashToViewIdByTime: Map[CantonTimestamp, Map[ByteString, Int]],
  )(implicit tc: TraceContext) = {
    client
      .getTrafficSummaries(sequencingTimes)
      .map(_.map { proto =>
        DbScanVerdictStore
          .fromProtoWithCorrelation(proto, viewHashToViewIdByTime, logger)
      })
      // TODO(#4060): handle missing traffic summaries more robustly. In particular,
      // note that the whole call will fail if ANY of the requested traffic summaries are missing.
      // This workaround may therefore drop existing traffic summaries.
      .recoverWith { case ex @ GrpcException(status, trailers) =>
        val statusProto = StatusProto.fromStatusAndTrailers(status, trailers)
        val errorDetails = ErrorDetails.from(statusProto)
        val errorCodeId = errorDetails
          .flatMap {
            case ed: ErrorDetails.ErrorInfoDetail =>
              Some(ed.errorCodeId)
            case _ => None
          }
          .headOption
          .getOrElse("none")
        if (errorCodeId == TrafficControlErrors.NoEventAtTimestamps.id)
          Future.successful(Seq.empty)
        else
          Future.failed(ex)
      }
  }

  /** Build sequencing times and a map for correlating sequencer traffic data with verdict views.
    *
    * Returns a tuple of:
    * - sequencing times (record_time) from the verdicts, preserving order
    * - a map from sequencing_time to (view_hash -> view_id) mappings
    *
    * The sequencer provides view_hashes in its traffic summaries, which we map
    * to view_ids from the verdict's transaction views.
    */
  def buildViewHashCorrelation(
      verdicts: Seq[v30.Verdict]
  ): (Seq[CantonTimestamp], Map[CantonTimestamp, Map[ByteString, Int]]) = {
    val pairs = verdicts.map { verdict =>
      val recordTime = CantonTimestamp
        .fromProtoTimestamp(verdict.getRecordTime)
        .getOrElse(throw new IllegalArgumentException("Invalid record_time in verdict"))
      val viewHashMap: Map[ByteString, Int] = verdict.getTransactionViews.views.collect {
        case (viewId, txView) if !txView.viewHash.isEmpty =>
          txView.viewHash -> viewId
      }.toMap
      (recordTime, viewHashMap)
    }
    (pairs.map(_._1), pairs.toMap)
  }

  private def processWhenUnpaused(
      input: (Seq[v30.Verdict], Seq[DbScanVerdictStore.TrafficSummaryT])
  )(implicit traceContext: TraceContext): Future[Unit] = {
    // If paused, this step will backpressure the source
    waitForResume().flatMap { _ =>
      ingestionMetrics.latency.timeFuture(process(input))
    }
  }

  private def batchSource[T, Mat](source: Source[T, Mat]): Source[Seq[T], Mat] =
    source.batch(math.max(1, config.mediatorVerdictIngestion.batchSize.toLong), Vector(_))(_ :+ _)

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = super
    .closeAsync()
    .appendedAll(
      Seq(
        SyncCloseable("current mediator", currentMediatorClient.close()),
        SyncCloseable("successor mediator", successorMediatorClientO.foreach(_.close())),
      )
    )
  // Kick-off the ingestion
  start()

}
