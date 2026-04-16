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

final case class StoreReadPerfMetrics(
    totalReads: Long,
    totalTimeNs: BigDecimal,
    processCpuTimeNs: BigDecimal,
    peakHeapBytes: BigDecimal,
    readResults: Seq[ReadOperationResult],
) {
  def avgReadTimeNs: BigDecimal =
    if (totalReads > 0) totalTimeNs / totalReads else BigDecimal(0)

  def cpuToWallClockRatio: BigDecimal =
    if (totalTimeNs > 0) processCpuTimeNs / totalTimeNs else BigDecimal(0)
}

final case class ReadOperationResult(
    name: String,
    iterations: Int,
    totalTimeNs: BigDecimal,
    avgTimeNs: BigDecimal,
    resultCount: Long,
)

/** A named read operation to benchmark against the store.
  * @param name human-readable name for this read operation
  * @param iterations number of times to repeat the read for averaging
  * @param execute function that performs the read and returns the number of result items
  */
final case class ReadOperation(
    name: String,
    iterations: Int,
    execute: TraceContext => Future[Long],
)

/** Base class for store read performance tests.
  *
  * This reuses the ingestion infrastructure: it loads data from a JSON dump,
  * ingests it into the store, and then benchmarks a set of read operations.
  */
abstract class StoreReadPerformanceTest(
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

  /** Define the read operations to benchmark. Called after ingestion completes. */
  protected def readOperations(store: Store): Seq[ReadOperation]

  /** A short name for this test, used as the metrics file name. */
  protected def testName: String = this.getClass.getSimpleName.stripSuffix("$")

  def run(): Future[Unit] = {
    val storage = initializeStorage()
    val store = mkStore(storage)
    TraceContext
      .withNewTraceContext(this.getClass.getName) { implicit tc =>
        for {
          _ <- store.ingestionSink.initialize()
          txs = loadTxsFromDump()
          _ <- ingestAll(store, txs)
          metrics <- runReadBenchmarks(store)
          _ = writeMetricsFile(metrics, success = true)
        } yield ()
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
          new DbStorageHistograms(MetricName("store", "readperftest"))(new HistogramInventory),
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

  private def loadTxsFromDump(): Seq[TreeUpdateWithMigrationId] = {
    val dump = parseDump()
    dump.transactions.zipWithIndex.collect {
      case (UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(update), index) =>
        CompactJsonScanHttpEncodings().httpToLapiTransaction(update, index.toLong)
    }
  }

  /** Load update IDs from the dump file. Subclasses use this to know which IDs to read back. */
  protected def loadUpdateIds(): Seq[String] = {
    loadTxsFromDump().map(_.update.update.updateId)
  }

  private def parseDump(): UpdateHistoryResponseV2 = {
    (for {
      json <- io.circe.parser.parse(Files.readString(updateHistoryDumpPath))
      decoded <- UpdateHistoryResponseV2.decodeUpdateHistoryResponseV2.decodeJson(json)
    } yield decoded)
      .getOrElse(
        throw new IllegalArgumentException(
          s"Failed to parse the update history from $updateHistoryDumpPath. It should have the structure of UpdateHistoryResponseV2."
        )
      )
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def ingestAll(store: Store, txs: Seq[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[Unit] = {
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
        logger.info(s"Ingesting batch $index of ${batch.length} elements for read test setup")
        store.ingestionSink
          .ingestUpdateBatch(NonEmptyList.fromListUnsafe(batch))
          .map(_ => ())
      })
      .map { _ =>
        println(s"${this.getClass.getName}: Ingestion complete, starting read benchmarks")
      }
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def runReadBenchmarks(store: Store)(implicit
      tc: TraceContext
  ): Future[StoreReadPerfMetrics] = {
    val ops = readOperations(store)
    var totalTimeNs = BigDecimal(0)
    var totalReads = 0L
    var totalCpuNs = BigDecimal(0)
    var maxHeapBytes = BigDecimal(0)

    def benchmarkOp(op: ReadOperation): Future[ReadOperationResult] = {
      var opTotalTimeNs = BigDecimal(0)
      var lastResultCount = 0L

      def runIteration(i: Int): Future[Unit] = {
        if (i >= op.iterations) Future.successful(())
        else {
          val wallBefore = System.nanoTime()
          val cpuBefore = getProcessCpuTimeNs
          op.execute(tc).flatMap { resultCount =>
            val wallAfter = System.nanoTime()
            val cpuAfter = getProcessCpuTimeNs
            val duration = wallAfter - wallBefore
            val cpuDeltaNs = math.max(cpuAfter - cpuBefore, 0L)
            opTotalTimeNs += duration
            totalTimeNs += duration
            totalCpuNs += cpuDeltaNs
            totalReads += 1
            val heapNow = getHeapUsedBytes
            maxHeapBytes = maxHeapBytes.max(heapNow)
            lastResultCount = resultCount
            runIteration(i + 1)
          }
        }
      }

      runIteration(0).map { _ =>
        val avgTimeNs = if (op.iterations > 0) opTotalTimeNs / op.iterations else BigDecimal(0)
        val msg =
          f"Read '${op.name}': ${ op.iterations } iterations, avg=${avgTimeNs / 1e6}%.2f ms, total=${opTotalTimeNs / 1e6}%.2f ms, results=$lastResultCount"
        logger.info(msg)
        println(s"${this.getClass.getName}: $msg")
        ReadOperationResult(
          name = op.name,
          iterations = op.iterations,
          totalTimeNs = opTotalTimeNs,
          avgTimeNs = avgTimeNs,
          resultCount = lastResultCount,
        )
      }
    }

    // Run operations sequentially
    ops
      .foldLeft(Future.successful(Seq.empty[ReadOperationResult])) { (accF, op) =>
        accF.flatMap { acc =>
          benchmarkOp(op).map(acc :+ _)
        }
      }
      .map { results =>
        println(
          f"Process-level metrics: CPU time=${totalCpuNs / 1e6}%.2f ms, peak heap=$maxHeapBytes bytes"
        )
        StoreReadPerfMetrics(
          totalReads = totalReads,
          totalTimeNs = totalTimeNs,
          processCpuTimeNs = totalCpuNs,
          peakHeapBytes = maxHeapBytes,
          readResults = results,
        )
      }
  }

  private def getProcessCpuTimeNs: Long = {
    ManagementFactory.getOperatingSystemMXBean match {
      case osBean: com.sun.management.OperatingSystemMXBean => osBean.getProcessCpuTime
      case _ => -1L
    }
  }

  private def getHeapUsedBytes: BigDecimal = {
    BigDecimal(ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed)
  }

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  private def writeMetricsFile(metrics: StoreReadPerfMetrics, success: Boolean): Unit = {
    val completionEpochSec = java.time.Instant.now().getEpochSecond
    val successInt = if (success) 1 else 0
    val jobName = "splice_perf_read"

    def metric(metricName: String, description: String, value: BigDecimal): Json = Json.obj(
      "name" -> Json.fromString(s"${jobName}_$metricName"),
      "description" -> Json.fromString(description),
      "value" -> Json.fromBigDecimal(value),
    )

    val perOpMetrics: Seq[Json] = metrics.readResults.map { r =>
      Json.obj(
        "name" -> Json.fromString(s"${jobName}_op_${r.name}"),
        "iterations" -> Json.fromInt(r.iterations),
        "avg_time_ns" -> Json.fromBigDecimal(r.avgTimeNs),
        "total_time_ns" -> Json.fromBigDecimal(r.totalTimeNs),
        "result_count" -> Json.fromLong(r.resultCount),
      )
    }

    val json = Json
      .obj(
        "test_name" -> Json.fromString(testName),
        "metrics" -> Json.arr(
          metric(
            "avg_read_time_ns",
            "Average nanoseconds per read operation",
            metrics.avgReadTimeNs,
          ),
          metric("total_reads", "Total number of read operations executed", BigDecimal(metrics.totalReads)),
          metric("total_time_ns", "Total read time in nanoseconds", metrics.totalTimeNs),
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
            "Peak JVM heap memory usage in bytes observed across all read operations",
            metrics.peakHeapBytes,
          ),
          metric(
            "cpu_to_wall_clock_ratio",
            "Ratio of process CPU time to wall-clock time",
            metrics.cpuToWallClockRatio,
          ),
        ),
        "read_operations" -> Json.arr(perOpMetrics*),
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

  protected def mkParticipantId(name: String): ParticipantId =
    ParticipantId.tryFromProtoPrimitive("PAR::" + name + "::dummy")
}
