package org.lfdecentralizedtrust.splice.performance.tests

import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.TreeUpdateWithMigrationId

import io.circe.Json
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import scala.concurrent.{ExecutionContext, Future}

final case class StoreReadPerfMetrics(
    totalTimeNs: BigDecimal,
    processCpuTimeNs: BigDecimal,
    peakHeapBytes: BigDecimal,
) {
  def cpuToWallClockRatio: BigDecimal =
    if (totalTimeNs > 0) processCpuTimeNs / totalTimeNs else BigDecimal(0)
}

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
          metrics <- runReadBenchmarks(store, txs)
          _ <- verifyReadResults(store, txs)
          _ = writeMetricsFile(metrics)
        } yield ()
      }
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def runReadBenchmarks(store: Store, txs: Seq[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[StoreReadPerfMetrics] = {
    val op = readOperation(store, txs)
    val wallBefore = System.nanoTime()
    val cpuBefore = getProcessCpuTimeNs
    op.execute(tc).map { _ =>
      val wallAfter = System.nanoTime()
      val cpuAfter = getProcessCpuTimeNs
      val duration = wallAfter - wallBefore
      val cpuDeltaNs = math.max(cpuAfter - cpuBefore, 0L)
      val heapUsed = getHeapUsedBytes

      val msg =
        f"Read '${op.name}': time=${BigDecimal(duration) / 1e6}%.2f ms"
      logger.info(msg)
      println(s"${this.getClass.getName}: $msg")
      println(
        f"Process-level metrics: CPU time=${BigDecimal(cpuDeltaNs) / 1e6}%.2f ms, peak heap=$heapUsed bytes"
      )

      StoreReadPerfMetrics(
        totalTimeNs = BigDecimal(duration),
        processCpuTimeNs = BigDecimal(cpuDeltaNs),
        peakHeapBytes = heapUsed,
      )
    }
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def writeMetricsFile(metrics: StoreReadPerfMetrics): Unit = {
    val completionEpochSec = java.time.Instant.now().getEpochSecond
    val jobName = "splice_perf_read"

    def metric(metricName: String, description: String, value: BigDecimal): Json = Json.obj(
      "name" -> Json.fromString(s"${jobName}_$metricName"),
      "description" -> Json.fromString(description),
      "value" -> Json.fromBigDecimal(value),
    )

    val json = Json
      .obj(
        "test_name" -> Json.fromString(testName),
        "metrics" -> Json.arr(
          metric("total_time_ns", "Total read time in nanoseconds", metrics.totalTimeNs),
          metric(
            "completion_timestamp_seconds",
            "Epoch seconds when the test run completed",
            BigDecimal(completionEpochSec),
          ),
          metric(
            "process_cpu_time_ns",
            "Process-wide CPU time in nanoseconds (all cores combined)",
            metrics.processCpuTimeNs,
          ),
          metric(
            "peak_heap_bytes",
            "Peak JVM heap memory usage in bytes observed during read",
            metrics.peakHeapBytes,
          ),
          metric(
            "cpu_to_wall_clock_ratio",
            "Ratio of process CPU time to wall-clock time",
            metrics.cpuToWallClockRatio,
          ),
        ),
      )
      .spaces2

    println(s"Read metrics for $testName:\n$json")

    try {
      val metricsDir = Paths.get("/tmp/store-read-perf-metrics")
      Files.createDirectories(metricsDir)
      val metricsFile = metricsDir.resolve(s"$testName.json")
      Files.writeString(
        metricsFile,
        json,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
      )
      println(s"Wrote metrics to $metricsFile")
    } catch {
      case e: Exception =>
        println(s"Failed to write metrics file for $testName: ${e.getMessage}")
    }
  }
}
