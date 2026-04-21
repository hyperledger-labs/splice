package org.lfdecentralizedtrust.splice.performance.tests

import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.TreeUpdateOrOffsetCheckpoint
import org.lfdecentralizedtrust.splice.store.TreeUpdateWithMigrationId

import cats.data.NonEmptyList
import io.circe.Json
import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

final case class StoreIngestionPerfMetrics(
    totalItems: Long,
    totalBatches: Long,
    totalTimeNs: BigDecimal,
    processCpuTimeNs: BigDecimal,
    peakHeapBytes: BigDecimal,
) {
  def avgItemTimeNs: BigDecimal =
    if (totalItems > 0) totalTimeNs / totalItems else BigDecimal(0)

  /** Ratio of process CPU time to wall-clock time.
    * Some thresholds we can use for rough classification:
    * > 0.7 : CPU-bound
    * 0.7 ~ 0.25: balanced
    * < 0.25 : I/O-bound
    */
  def cpuToWallClockRatio: BigDecimal =
    if (totalTimeNs > 0) processCpuTimeNs / totalTimeNs else BigDecimal(0)
}

abstract class StoreIngestionPerformanceTest(
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

  def run(): Future[Unit] = {
    val storage = initializeStorage()
    val store = mkStore(storage)
    TraceContext
      .withNewTraceContext(this.getClass.getName) { implicit tc =>
        (for {
          _ <- store.ingestionSink.initialize()
          _ <- sanityCheckTables(storage) { count =>
            Option.when(count != 0)(
              s"Expected table to be empty before ingestion, but found $count rows."
            )
          }
          txs = loadTxsFromDump()
          metrics <- ingestWithMetrics(store, txs)
          _ <- sanityCheckTables(storage) { count =>
            Option.when(count == 0)(
              s"Expected table to be non-empty after ingestion, but no rows were inserted."
            )
          }
          _ = writeIngestionMetrics(metrics, success = true)
        } yield ())
      }
  }

  // Ensuring that it's logged also on GHA console, as opposed to only in log files (which are not uploaded on success)
  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def ingestWithMetrics(store: Store, txs: Seq[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[StoreIngestionPerfMetrics] = {
    var totalTimeNs = BigDecimal(0)
    var totalItems = 0L
    var totalBatches = 0L
    var totalCpuNs = BigDecimal(0)
    var maxHeapBytes = BigDecimal(0)

    Source
      .fromIterator(() => txs.iterator)
      .batch(ingestionConfig.maxBatchSize.toLong, Vector(_))(_ :+ _)
      .map(batch =>
        batch
          .map(tx =>
            TreeUpdateOrOffsetCheckpoint.Update(tx.update.update, tx.update.synchronizerId)
          )
          .toList
      )
      .zipWithIndex
      .runWith(Sink.foreachAsync(parallelism = 1) { case (batch, index) =>
        logger.info(s"Ingesting batch $index of ${batch.length} elements")
        val wallBefore = System.nanoTime()
        val cpuBefore = getProcessCpuTimeNs
        store.ingestionSink
          .ingestUpdateBatch(NonEmptyList.fromListUnsafe(batch))
          .map { _ =>
            val wallAfter = System.nanoTime()
            val cpuAfter = getProcessCpuTimeNs
            val duration = wallAfter - wallBefore
            val cpuDeltaNs = math.max(cpuAfter - cpuBefore, 0L)
            totalTimeNs += duration
            totalCpuNs += cpuDeltaNs
            val heapNow = getHeapUsedBytes
            maxHeapBytes = maxHeapBytes.max(heapNow)
            totalItems += batch.length
            totalBatches += 1
            val avg = totalTimeNs / totalItems
            val msg =
              f"Ingested batch $index (${batch.length} elements) in $duration ns, average per-item time: $avg%.2f ns over $totalItems records, total time: $totalTimeNs ns"
            logger.info(msg)
            println(s"${this.getClass.getName}: $msg")
          }
      })
      .map { _ =>
        println(
          f"Process-level metrics: CPU time=${totalCpuNs / 1e6}%.2f ms, peak heap=$maxHeapBytes bytes"
        )

        StoreIngestionPerfMetrics(
          totalItems = totalItems,
          totalBatches = totalBatches,
          totalTimeNs = totalTimeNs,
          processCpuTimeNs = totalCpuNs,
          peakHeapBytes = maxHeapBytes,
        )
      }
  }

  /** A separate Python script pushes the metrics to Prometheus Pushgateway.
    * We structure JSON file making the python side mostly readonly, enabling to add new metrics to JSON without changing python script.
    */
  private def writeIngestionMetrics(metrics: StoreIngestionPerfMetrics, success: Boolean): Unit = {
    val completionEpochSec = java.time.Instant.now().getEpochSecond
    val successInt = if (success) 1 else 0
    val jobName = "splice_perf_ingestion"

    def metric(metricName: String, description: String, value: BigDecimal): Json = Json.obj(
      "name" -> Json.fromString(s"${jobName}_$metricName"),
      "description" -> Json.fromString(description),
      "value" -> Json.fromBigDecimal(value),
    )

    val metricsJson = Json.arr(
      metric(
        "avg_item_time_ns",
        "Average nanoseconds per ingested item",
        metrics.avgItemTimeNs,
      ),
      metric("total_items", "Total number of items ingested", BigDecimal(metrics.totalItems)),
      metric("total_time_ns", "Total ingestion time in nanoseconds", metrics.totalTimeNs),
      metric(
        "total_batches",
        "Total number of batches ingested",
        BigDecimal(metrics.totalBatches),
      ),
      metric(
        "completion_timestamp_seconds",
        "Epoch seconds when the test run completed",
        BigDecimal(completionEpochSec),
      ),
      metric("success", "Test run succeeded (1) or failed (0)", BigDecimal(successInt)),
      metric(
        "process_cpu_time_ns",
        "Process-wide CPU time in nanoseconds (all cores combined)",
        metrics.processCpuTimeNs,
      ),
      metric(
        "peak_heap_bytes",
        "Peak JVM heap memory usage in bytes observed across all ingestion batches",
        metrics.peakHeapBytes,
      ),
      metric(
        "cpu_to_wall_clock_ratio",
        "Ratio of process CPU time to wall-clock time (>0.7=CPU-bound, 0.25~0.7=standard, <0.25=I/O-bound)",
        metrics.cpuToWallClockRatio,
      ),
    )

    writeMetricsFile(metricsJson, "store-ingestion-perf-metrics", "Ingestion")
  }
}
