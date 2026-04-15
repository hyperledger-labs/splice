// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.grpc.GrpcException
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.metrics.api.MetricsContext
import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.sequencing.traffic.TrafficControlErrors
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.protobuf.StatusProto
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.automation.RetryingService
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, ServiceWithGuaranteedShutdown}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.mediator.MediatorVerdictsClient
import org.lfdecentralizedtrust.splice.scan.metrics.ScanMediatorVerdictIngestionMetrics
import org.lfdecentralizedtrust.splice.scan.rewards.AppActivityComputation
import org.lfdecentralizedtrust.splice.scan.sequencer.SequencerTrafficClient
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.scan.ScanSynchronizerNode

import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

/** Ingestion for verdict ingestion store. */
class ScanVerdictIngestionService(
    config: ScanAppBackendConfig,
    synchronizerNodes: LocalSynchronizerNodes[ScanSynchronizerNode],
    grpcClientMetrics: GrpcClientMetrics,
    store: DbScanVerdictStore,
    migrationId: Long,
    synchronizerId: SynchronizerId,
    ingestionMetrics: ScanMediatorVerdictIngestionMetrics,
    sequencerTrafficClientO: Option[SequencerTrafficClient],
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
  ): Future[ServiceWithGuaranteedShutdown[Seq[v30.Verdict]]] =
    for {
      _ <- waitForStores()
      ingestionStart <- getIngestionStart()
      announcements <- synchronizerNodes.current.sequencerAdminConnection
        .listLsuAnnouncements(synchronizerId)
      currentPsid <- synchronizerNodes.current.sequencerAdminConnection
        .getPhysicalSynchronizerId()
    } yield {
      val pastUpgradeAnnouncement = announcements.find { announcement =>
        ingestionStart.isAfter(announcement.mapping.upgradeTime) &&
        announcement.mapping.successorSynchronizerId != currentPsid
      }
      val source = pastUpgradeAnnouncement match {
        case Some(announcement) =>
          logger.info(
            s"Ingestion start $ingestionStart is after LSU upgrade time ${announcement.mapping.upgradeTime}, " +
              s"skipping current mediator client and using successor directly"
          )
          successorMediatorClientO match {
            case Some(successorMediatorClient) =>
              successorMediatorClient
                .streamVerdicts(Some(ingestionStart))
                .mapMaterializedValue(_ => NotUsed)
            case None =>
              logger.error(
                "Ingestion start is past LSU upgrade time but no successor mediator client is configured"
              )
              Source.empty[v30.Verdict]
          }
        case None =>
          logger.info(s"Streaming verdicts starting from $ingestionStart")
          val currentSource = currentMediatorClient.streamVerdicts(Some(ingestionStart))
          val completedWithCompleteF = Promise[Option[v30.VerdictsResponse.Complete]]()
          currentSource
            .mapMaterializedValue { completeFuture =>
              completeFuture.foreach { result =>
                completedWithCompleteF.trySuccess(result).discard
                result match {
                  case Some(complete) =>
                    logger.info(
                      s"Current mediator verdicts stream completed with: $complete, closing current client"
                    )
                    currentMediatorClient.close()
                  case None =>
                }
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
                            successorMediatorClient
                              .streamVerdicts(Some(successorIngestionStart))
                              .mapMaterializedValue(_ => NotUsed)
                          case None =>
                            logger.error(
                              "Current mediator verdicts stream completed but no successor mediator client is configured"
                            )
                            Source.empty[v30.Verdict]
                        }
                      }
                    case None =>
                      Future.successful(Source.empty[v30.Verdict])
                  }
                )
                .mapMaterializedValue(_ => NotUsed)
            )
      }
      new ServiceWithGuaranteedShutdown(
        source = batchSource(source),
        map = processWhenUnpaused,
        retryProvider = retryProvider,
        loggerFactory = loggerFactory.append("subsClient", this.getClass.getSimpleName),
      )
    }

  private def process(batch: Seq[v30.Verdict])(implicit
      tc: TraceContext
  ): Future[Unit] = {
    if (batch.isEmpty) {
      logger.error(
        "Received empty batch of verdicts to ingest. This is never supposed to happen."
      )
      Future.successful(())
    } else {
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
          case None =>
            Future.successful(Seq.empty)
        }

      // 2. Insert verdicts, traffic summaries, and app activity records in a single transaction
      for {
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
          val recordTime = CantonTimestamp.tryFromProtoTimestamp(v.getRecordTime)
          summaryByTime.get(recordTime).map(_ -> v)
        }

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
        val lastRecordTime = batch.lastOption
          .flatMap(v => CantonTimestamp.fromProtoTimestamp(v.getRecordTime).toOption)
          .getOrElse(CantonTimestamp.MinValue)
        ingestionMetrics.lastIngestedRecordTime.updateValue(lastRecordTime)
        ingestionMetrics.verdictCount.mark(batch.size.toLong)(MetricsContext.Empty)
        ingestionMetrics.batchSize.update(batch.size.toLong)(MetricsContext.Empty)
        logger.info(
          s"Inserted ${batch.size} verdicts, ${trafficSummaries.size} traffic summaries, " +
            s"${appActivityRecords.size} app activity records. " +
            s"Last ingested verdict record_time is now ${store.lastIngestedRecordTime}. " +
            s"Inserted verdicts: ${batch.map(_.updateId)}"
        )
      }
    }
  }

  private def processWhenUnpaused(
      batch: Seq[v30.Verdict]
  )(implicit traceContext: TraceContext): Future[Unit] = {
    // If paused, this step will backpressure the source
    waitForResume().flatMap { _ =>
      ingestionMetrics.latency.timeFuture(process(batch))
    }
  }

  private def batchSource[T](source: Source[T, NotUsed]): Source[Seq[T], NotUsed] =
    source.batch(math.max(1, config.mediatorVerdictIngestion.batchSize.toLong), Vector(_))(_ :+ _)

  // Kick-off the ingestion
  start()

}
