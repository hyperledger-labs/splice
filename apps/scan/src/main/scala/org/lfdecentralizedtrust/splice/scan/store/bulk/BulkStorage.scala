package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.UniqueKillSwitch
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, UpdateHistory}

import scala.concurrent.ExecutionContext

class BulkStorage(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    currentMigrationId: Long,
    kvProvider: ScanKeyValueProvider,
    metricsFactory: LabeledMetricsFactory,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging
    with AutoCloseable {

  private val killSwitches = appConfig.s3config.fold {
    logger.debug("s3 connection not configured, not dumping to bulk storage")
    Seq.empty[UniqueKillSwitch]
  } { s3Config =>
    val s3Connection = S3BucketConnection(s3Config, loggerFactory)
    val historyMetrics = HistoryMetrics(metricsFactory, currentMigrationId)

    Seq(
      new AcsSnapshotBulkStorage(
        storageConfig,
        appConfig,
        acsSnapshotStore,
        updateHistory,
        s3Connection,
        kvProvider,
        historyMetrics,
        loggerFactory,
      ).getSource().toMat(Sink.ignore)(Keep.left).run(),
      new UpdateHistoryBulkStorage(
        storageConfig,
        appConfig,
        updateHistory,
        kvProvider,
        currentMigrationId,
        s3Connection,
        historyMetrics,
        loggerFactory,
      ).getSource().toMat(Sink.ignore)(Keep.left).run(),
    )

  }
  override def close(): Unit = {
    killSwitches.foreach(_.shutdown())
  }

}
