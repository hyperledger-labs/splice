package org.lfdecentralizedtrust.splice.performance.tests

import cats.data.NonEmptyList
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.metrics.api.{HistogramInventory, MetricName, MetricsContext}
import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.metrics.{DbStorageHistograms, DbStorageMetrics}
import com.digitalasset.canton.resource.{DbMigrations, DbStorage, StorageSingleFactory}
import com.digitalasset.canton.time.WallClock
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.TreeUpdateOrOffsetCheckpoint
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  UpdateHistoryItemV2,
  UpdateHistoryResponseV2,
}
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.HasIngestionSink
import org.lfdecentralizedtrust.splice.store.TreeUpdateWithMigrationId

import io.circe.Json
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

final case class StoreIngestionPerfMetrics(
    totalItems: Long,
    totalBatches: Long,
    totalTimeNs: BigDecimal,
    processCpuTimeNs: BigDecimal,
    gcTimeMs: BigDecimal,
    peakHeapBytes: BigDecimal,
) {
  def avgItemTimeNs: BigDecimal =
    if (totalItems > 0) totalTimeNs / totalItems else BigDecimal(0)

  /** Ratio of process CPU time to wall-clock time.
    * > 1.0 → CPU-bound: burned more CPU than wall-clock elapsed.
    * < 1.0 → I/O-bound: CPU idle during DB writes, network, OS scheduling.
    */
  def cpuToWallClockRatio: BigDecimal =
    if (totalTimeNs > 0) processCpuTimeNs / totalTimeNs else BigDecimal(0)
}

abstract class StoreIngestionPerformanceTest(
    updateHistoryDumpPath: Path,
    storageConfig: StorageConfig,
    override protected val timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    ingestionConfig: IngestionConfig = IngestionConfig(),
)(implicit ec: ExecutionContext, actorSystem: ActorSystem)
    extends FlagCloseable {

  override protected[this] val logger: TracedLogger =
    loggerFactory.getTracedLogger(this.getClass)

  protected implicit val closeContext: CloseContext = CloseContext(this)

  type Store <: HasIngestionSink
  protected def mkStore(storage: DbStorage): Store

  /** A short name for this test, used as the metrics file name and Pushgateway label. */
  protected def testName: String = this.getClass.getSimpleName.stripSuffix("$")

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
          metrics <- ingestAll(store, txs)
          _ <- sanityCheckTables(storage) { count =>
            Option.when(count == 0)(
              s"Expected table to be non-empty after ingestion, but no rows were inserted."
            )
          }
          _ = writeMetricsFile(metrics, success = true)
        } yield ())
      }
  }

  private def initializeStorage(): DbStorage = {
    val storageFactory = new StorageSingleFactory(storageConfig)
    val storage =
      storageFactory.tryCreate(
        connectionPoolForParticipant = false,
        None,
        new WallClock(timeouts, loggerFactory),
        None,
        new DbStorageMetrics(
          new DbStorageHistograms(MetricName("store", "perftest"))(new HistogramInventory),
          NoOpMetricsFactory,
        )(MetricsContext()),
        timeouts,
        loggerFactory,
      )(
        ec,
        TraceContext.empty,
        closeContext,
      ) match {
        case storage: DbStorage => storage
        case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
      }
    new DbMigrations(storage.dbConfig, false, timeouts, loggerFactory)
      .migrateDatabase()
      .map(_ => storage)
      .getOrElse(throw new RuntimeException("Failed to run migrations."))
      .onShutdown(throw new IllegalStateException("Shutdown should not be happening here"))
  }

  /** Load and parse all transactions in memory so that reading doesn't bottleneck.
    */
  private def loadTxsFromDump(): Seq[TreeUpdateWithMigrationId] = {
    val dump = (for {
      json <- io.circe.parser.parse(Files.readString(updateHistoryDumpPath))
      decoded <- UpdateHistoryResponseV2.decodeUpdateHistoryResponseV2.decodeJson(json)
    } yield decoded)
      .getOrElse(
        throw new IllegalArgumentException(
          s"Failed to parse the update history from $updateHistoryDumpPath. It should have the structure of UpdateHistoryResponseV2."
        )
      )
    dump.transactions.zipWithIndex.collect {
      // deliberately ignoring reassignments
      case (UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(update), index) =>
        CompactJsonScanHttpEncodings().httpToLapiTransaction(update, index.toLong)
    }
  }

  // Ensuring that it's logged also on GHA console, as opposed to only in log files (which are not uploaded on success)
  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def ingestAll(store: Store, txs: Seq[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[StoreIngestionPerfMetrics] = {
    var totalTimeNs = BigDecimal(0)
    var totalItems = 0L
    var totalBatches = 0L
    var totalCpuNs = BigDecimal(0)
    var totalGcMs = BigDecimal(0)
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
        val cpuSnapshotBefore = captureCpuSnapshot()
        val gcBefore = getTotalGcTimeMs
        store.ingestionSink
          .ingestUpdateBatch(NonEmptyList.fromListUnsafe(batch))
          .map { _ =>
            val wallAfter = System.nanoTime()
            val cpuSnapshotAfter = captureCpuSnapshot()
            val gcAfter = getTotalGcTimeMs
            val duration = wallAfter - wallBefore
            val cpuDeltaNs = cpuDelta(cpuSnapshotBefore, cpuSnapshotAfter)
            totalTimeNs += duration
            totalCpuNs += cpuDeltaNs
            totalGcMs += (gcAfter - gcBefore)
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
          f"Process-level metrics: CPU time=${totalCpuNs / 1e6}%.2f ms, GC time=$totalGcMs ms, peak heap=$maxHeapBytes bytes"
        )

        StoreIngestionPerfMetrics(
          totalItems = totalItems,
          totalBatches = totalBatches,
          totalTimeNs = totalTimeNs,
          processCpuTimeNs = totalCpuNs,
          gcTimeMs = totalGcMs,
          peakHeapBytes = maxHeapBytes,
        )
      }
  }

  /** Per-thread CPU time snapshot: threadId -> cpuTimeNs. */
  private type CpuSnapshot = Map[Long, Long]

  /** Capture per-thread total CPU time for all live threads. */
  private def captureCpuSnapshot(): CpuSnapshot = {
    val threadBean = ManagementFactory.getThreadMXBean
    if (threadBean.isThreadCpuTimeSupported && threadBean.isThreadCpuTimeEnabled) {
      threadBean.getAllThreadIds.flatMap { id =>
        val cpuNs = threadBean.getThreadCpuTime(id)
        if (cpuNs >= 0) Some(id -> cpuNs) else None
      }.toMap
    } else {
      Map.empty
    }
  }

  /** Compute total CPU delta between two snapshots, considering only threads
    * present in both.
    *
    * Background threads (GC, Pekko dispatchers, timers) can start or stop between
    * snapshots.
    * Common thread IDs of two snapshots ensure we ignore dead threads (avoids negative
    * deltas) and new threads (avoids counting unrelated work).
    *
    * The ingestion-runner thread is always present in both snapshots
    * (cpuSnapshotBefore and cpuSnapshotAfter) because `captureCpuSnapshot()` is called
    * within the runner thread before and after the ingestion of each batch,
    * so we are guaranteed to capture its CPU time deltas accurately.
    */
  private def cpuDelta(before: CpuSnapshot, after: CpuSnapshot): BigDecimal = {
    var delta = 0L
    after.foreach { case (id, cpuAfter) =>
      before.get(id).foreach { cpuBefore =>
        delta += math.max(cpuAfter - cpuBefore, 0L)
      }
    }
    BigDecimal(delta)
  }

  /** Total GC collection time across all collectors in milliseconds. */
  private def getTotalGcTimeMs: BigDecimal = {
    BigDecimal(
      ManagementFactory.getGarbageCollectorMXBeans.asScala
        .map(_.getCollectionTime)
        .filter(_ >= 0)
        .sum
    )
  }

  /** Process-wide current heap memory usage in bytes (point-in-time snapshot). */
  private def getHeapUsedBytes: BigDecimal = {
    BigDecimal(ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed)
  }

  /** A separate Python script pushes the metrics to Prometheus Pushgateway.
    * We structure JSON file making the python side mostly readonly, enabling to add new metrics to JSON without changing python script.
    */
  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def writeMetricsFile(metrics: StoreIngestionPerfMetrics, success: Boolean): Unit = {
    val completionEpochSec = java.time.Instant.now().getEpochSecond
    val successInt = if (success) 1 else 0
    val jobName = "splice_perf_ingestion"

    def metric(metricName: String, description: String, value: BigDecimal): Json = Json.obj(
      "name" -> Json.fromString(s"${jobName}_$metricName"),
      "description" -> Json.fromString(description),
      "value" -> Json.fromBigDecimal(value),
    )

    val json = Json
      .obj(
        "test_name" -> Json.fromString(testName),
        "metrics" -> Json.arr(
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
            "gc_time_ms",
            "Total garbage collection time in milliseconds",
            metrics.gcTimeMs,
          ),
          metric(
            "peak_heap_bytes",
            "Peak JVM heap memory usage in bytes observed across all ingestion batches",
            metrics.peakHeapBytes,
          ),
          metric(
            "cpu_to_wall_clock_ratio",
            "Ratio of process CPU time to wall-clock time (>1=CPU-bound, ~1=saturated, <1=I/O-bound)",
            metrics.cpuToWallClockRatio,
          ),
        ),
      )
      .spaces2

    println(s"Ingestion metrics for $testName:\n$json")

    try {
      val metricsDir = Paths.get("/tmp/store-ingestion-perf-metrics")
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

  protected val tablesToSanityCheck: Seq[String]
  private type ErrorMessage = String

  private def sanityCheckTables(
      storage: DbStorage
  )(check: Int => Option[ErrorMessage])(implicit tc: TraceContext) = {
    import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
    Future.traverse(tablesToSanityCheck) { tableName =>
      storage
        .querySingle(
          sql"select count(*) from #$tableName".as[Int].headOption,
          s"sanityCheck-$tableName",
        )
        .value
        .failOnShutdownToAbortException("Should not be shutting down.")
        .flatMap {
          case Some(count) =>
            check(count).fold {
              Future.successful(
                logger.info(s"Sanity check passed for table $tableName with count $count.")
              )
            } { errMsg =>
              Future.failed(
                new IllegalStateException(s"Sanity check failed for table $tableName: $errMsg")
              )
            }
          case None =>
            Future.failed(
              new IllegalStateException(
                s"Sanity check failed for table $tableName. Row Count was None."
              )
            )
        }
    }
  }

  protected def mkParticipantId(name: String): ParticipantId =
    ParticipantId.tryFromProtoPrimitive("PAR::" + name + "::dummy")
}
