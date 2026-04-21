package org.lfdecentralizedtrust.splice.performance.tests

import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.TreeUpdateWithMigrationId

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}

final case class StoreReadPerfMetrics(
    totalTimeNs: BigDecimal,
    peakHeapBytes: BigDecimal,
    cpuToWallClockRatio: BigDecimal,
    updateSizeBytes: Long,
    numUpdates: Long,
)

final case class ReadOperation(
    name: String,
    execute: TraceContext => Future[Unit],
)

/** Base class for store read performance tests.
  *
  * This reuses the ingestion infrastructure: it loads data from a JSON dump,
  * ingests it into the store, and then benchmarks a read operation.
  */
abstract class StoreReadPerformanceTest(
    updateHistoryDumpPath: Path,
    storageConfig: StorageConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    ingestionConfig: IngestionConfig = IngestionConfig(),
)(implicit ec: ExecutionContext, actorSystem: ActorSystem)
    extends BaseStorePerformanceTest(
      updateHistoryDumpPath,
      storageConfig,
      timeouts,
      loggerFactory,
      ingestionConfig,
    ) {

  protected def readOperation(store: Store, txs: Seq[TreeUpdateWithMigrationId]): ReadOperation

  /** Verify that the data read back from the store matches the original ingested data. */
  protected def verifyReadResults(
      store: Store,
      originalTxs: Seq[TreeUpdateWithMigrationId],
  )(implicit tc: TraceContext): Future[Unit]

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  def run(): Future[Unit] = {
    val storage = initializeStorage()
    val store = mkStore(storage)
    TraceContext
      .withNewTraceContext(this.getClass.getName) { implicit tc =>
        for {
          _ <- store.ingestionSink.initialize()
          txs = loadTxsFromDump()
          _ <- ingestAll(store, txs)

          /** TODO(#4790): We need to address the temporal locality here,
            * we just write and read immediately, which is not what we have in production.
            *  Need to flush the caches(db etc)
            */
          metrics <- runReadBenchmarks(store, txs)
          _ <- verifyReadResults(store, txs)
          _ = writeReadMetrics(metrics)
        } yield ()
      }
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def writeReadMetrics(metrics: StoreReadPerfMetrics): Unit = {
    val completionEpochSec = java.time.Instant.now().getEpochSecond
    val jobName = "splice_perf_read"

    def metric(metricName: String, description: String, value: BigDecimal): Json = Json.obj(
      "name" -> Json.fromString(s"${jobName}_$metricName"),
      "description" -> Json.fromString(description),
      "value" -> Json.fromBigDecimal(value),
    )

    val metricsJson = Json.arr(
      metric("total_time_ns", "Total read time in nanoseconds", metrics.totalTimeNs),
      metric(
        "completion_timestamp_seconds",
        "Epoch seconds when the test run completed",
        BigDecimal(completionEpochSec),
      ),
      metric(
        "peak_heap_bytes",
        "Peak JVM heap memory usage in bytes observed during read",
        metrics.peakHeapBytes,
      ),
      metric(
        "cpu_to_wall_clock_ratio",
        "Ratio of process CPU time to wall-clock time (>0.7=CPU-bound, 0.25~0.7=standard, <0.25=I/O-bound)",
        metrics.cpuToWallClockRatio,
      ),
      metric(
        "update_size_bytes",
        "Size of the update dump file in bytes",
        BigDecimal(metrics.updateSizeBytes),
      ),
      metric(
        "num_updates",
        "Number of updates read",
        BigDecimal(metrics.numUpdates),
      ),
    )

    writeMetricsFile(metricsJson, "store-read-perf-metrics", "Read")
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def runReadBenchmarks(store: Store, txs: Seq[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[StoreReadPerfMetrics] = {
    val op = readOperation(store, txs)
    val updateSizeBytes = Files.size(updateHistoryDumpPath)
    val wallBefore = System.nanoTime()
    val cpuBefore = getProcessCpuTimeNs
    op.execute(tc).map { _ =>
      val wallAfter = System.nanoTime()
      val cpuAfter = getProcessCpuTimeNs
      val duration = wallAfter - wallBefore
      val cpuDeltaNs = math.max(cpuAfter - cpuBefore, 0L)
      val heapUsed = getHeapUsedBytes
      val cpuToWallClockRatio =
        if (duration > 0) BigDecimal(cpuDeltaNs) / BigDecimal(duration) else BigDecimal(0)

      val msg =
        f"Read '${op.name}': time=${BigDecimal(duration) / 1e6}%.2f ms, updateSize=$updateSizeBytes bytes, numUpdates=${txs.size}"
      logger.info(msg)
      println(s"${this.getClass.getName}: $msg")

      StoreReadPerfMetrics(
        totalTimeNs = BigDecimal(duration),
        peakHeapBytes = heapUsed,
        cpuToWallClockRatio = cpuToWallClockRatio,
        updateSizeBytes = updateSizeBytes,
        numUpdates = txs.size.toLong,
      )
    }
  }

}
