package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
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
    s3Connection: S3BucketConnection,
    kvProvider: ScanKeyValueProvider,
    historyMetrics: HistoryMetrics,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging with AutoCloseable {

  private val acsSnapshotsKillSwitch = new AcsSnapshotBulkStorage(
    storageConfig,
    appConfig,
    acsSnapshotStore,
    updateHistory,
    s3Connection,
    kvProvider,
    historyMetrics,
    loggerFactory,
  ).getSource().toMat(Sink.ignore)(Keep.left).run()

  private val updatesKillSwitch = new UpdateHistoryBulkStorage(
    storageConfig,
    appConfig,
    updateHistory,
    kvProvider,
    currentMigrationId,
    s3Connection,
    historyMetrics,
    loggerFactory,
  ).getSource().toMat(Sink.ignore)(Keep.left).run()

  override def close(): Unit = {
    acsSnapshotsKillSwitch.shutdown()
    updatesKillSwitch.shutdown()
  }

}
