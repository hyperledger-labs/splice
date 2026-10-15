package org.lfdecentralizedtrust.splice.performance.tests

import cats.data.NonEmptyList
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.metrics.api.{HistogramInventory, MetricName, MetricsContext}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.metrics.{DbStorageHistograms, DbStorageMetrics}
import com.digitalasset.canton.resource.{DbLockCounter, DbMigrations, DbStorage}
import com.digitalasset.canton.time.WallClock
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.SpliceStorageFactory
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
import java.nio.file.{Files, Path}
import java.nio.file.{Paths, StandardOpenOption}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object BaseStorePerformanceTest {

  /** Dedicated DB advisory-lock counters for the performance suite.
    *
    * Chosen above Canton's range (1–76) and Splice's app range (100–107, see
    * `SpliceDbLockCounters`) to avoid collisions. These are object-level vals so each counter is
    * registered with `DbLockCounter`'s global uniqueness check exactly once per JVM, even when
    * multiple perf-test classes run in the same sbt session.
    */
  private val PerfMainLockCounter: DbLockCounter = DbLockCounter(108)
  private val PerfPoolLockCounter: DbLockCounter = DbLockCounter(109)
}

/** Shared base class for store ingestion and read performance tests.
  *
  * Provides common infrastructure: storage initialization, dump loading,
  * ingestion, sanity checks, and JVM metrics helpers.
  */
abstract class BaseStorePerformanceTest(
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

  /** Whether to acquire the production DB instance lock (i.e. use `DbStorageMulti`).
    *
    * Defaults to `true` so the perf suite exercises the same storage path as production apps
    * (which default to `instanceLockEnabled = true` → `DbStorageMulti` on Postgres). Override to
    * `false` to measure the unlocked `DbStorageSingle` path for comparison.
    */
  protected def instanceLockEnabled: Boolean = true

  protected def initializeStorage(): DbStorage = {
    val metrics = new DbStorageMetrics(
      new DbStorageHistograms(MetricName("store", "perftest"))(new HistogramInventory),
      NoOpMetricsFactory,
    )(MetricsContext())
    // Build storage through the production factory so the perf suite exercises DbStorageMulti
    // (write-connection pool holding an exclusive advisory lock) exactly as the apps do, rather
    // than bypassing it with a hard-coded StorageSingleFactory.
    val storage =
      SpliceStorageFactory.createWithDeferredClose[DbStorage](
        storage = storageConfig,
        instanceLockEnabled = instanceLockEnabled,
        mainLockCounter = BaseStorePerformanceTest.PerfMainLockCounter,
        poolLockCounter = BaseStorePerformanceTest.PerfPoolLockCounter,
        exitOnFatalFailures = false,
        futureSupervisor = FutureSupervisor.Noop,
        loggerFactory = loggerFactory,
      ) { storageFactory =>
        storageFactory.tryCreate(
          connectionPoolForParticipant = false,
          None,
          new WallClock(timeouts, loggerFactory),
          None,
          metrics,
          timeouts,
          loggerFactory,
        )(
          ec,
          TraceContext.empty,
          closeContext,
        ) match {
          case dbStorage: DbStorage => Right(dbStorage)
          case storageType => Left(s"Unsupported storage type $storageType")
        }
      } match {
        case Right(s) => s
        case Left(err) => throw new RuntimeException(s"Failed to create storage: $err")
      }

    // Running unforked puts sbt's test jar on the classpath Flyway scans.
    // So, unlike in forked approach, Flyway finds the jar but can't read it.
    // Flyway skips it with a WARN. "Skipping unloadable jar file: ...apps-app_*-tests.jar" WARN.
    // That log line is handled via an ignore pattern in project/ignore-patterns/canton_network_test_log.ignore.txt
    // Migrations aren't affected — the actual migration files are read from the main resources.
    new DbMigrations(storage.dbConfig, false, timeouts, loggerFactory)
      .migrateDatabase()
      .map(_ => storage)
      .getOrElse(throw new RuntimeException("Failed to run migrations."))
      .onShutdown(throw new IllegalStateException("Shutdown should not be happening here"))
  }

  /** Initializes storage, runs `body`, and always closes the storage afterwards (on success or
    * failure).
    *
    * Closing is essential with `DbStorageMulti`: it releases the exclusive write-pool advisory
    * lock. The perf workflow runs several tests sequentially in a single, non-forked sbt JVM
    * (`apps-app / Test / fork = false`) against one shared database, so a leaked lock from one test
    * would block the next test's storage initialization.
    */
  protected def withStorage(body: DbStorage => Future[Unit]): Future[Unit] = {
    val storage = initializeStorage()
    val started =
      try body(storage)
      catch { case NonFatal(e) => Future.failed(e) }
    started.transformWith { result =>
      storage.close()
      Future.fromTry(result)
    }(ec)
  }

  /** Load and parse all transactions in memory so that reading doesn't bottleneck. */
  protected def loadTxsFromDump(): Seq[TreeUpdateWithMigrationId] = {
    val dump = (for {
      // streaming to fix: java.lang.OutOfMemoryError: Required array size too large
      json <- io.circe.jawn.parseFile(updateHistoryDumpPath.toFile)
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

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  protected def ingestAll(store: Store, txs: Seq[TreeUpdateWithMigrationId])(implicit
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
        logger.info(s"Ingesting batch $index of ${batch.length} elements")
        store.ingestionSink
          .ingestUpdateBatch(NonEmptyList.fromListUnsafe(batch))
          .map(_ => ())
      })
      .map { _ =>
        println(s"${this.getClass.getName}: Ingestion complete")
      }
  }

  protected val tablesToSanityCheck: Seq[String] = Seq.empty
  private type ErrorMessage = String

  protected def sanityCheckTables(
      storage: DbStorage
  )(check: Int => Option[ErrorMessage])(implicit tc: TraceContext): Future[Seq[Unit]] = {
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

  /** Process-wide CPU time in nanoseconds.
    * Returns -1 if not supported (treated as 0 by the caller via math.max).
    */
  protected def getProcessCpuTimeNs: Long = {
    ManagementFactory.getOperatingSystemMXBean match {
      case osBean: com.sun.management.OperatingSystemMXBean => osBean.getProcessCpuTime
      case _ => -1L
    }
  }

  /** Process-wide current heap memory usage in bytes (point-in-time snapshot). */
  protected def getHeapUsedBytes: BigDecimal = {
    BigDecimal(ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed)
  }

  protected def mkParticipantId(name: String): ParticipantId =
    ParticipantId.tryFromProtoPrimitive("PAR::" + name + "::dummy")

  @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.Println"))
  protected def writeMetricsFile(
      metricsJson: Json,
      metricsDir: String,
      pertTestType: String,
      opName: Option[String] = None,
  ): Unit = {
    val effectiveName = opName.fold(testName)(s => s"$testName-$s")
    val baseFields = Seq(
      "test_name" -> Json.fromString(effectiveName),
      "metrics" -> metricsJson,
    )
    val fields =
      opName.fold(baseFields)(op => baseFields :+ ("operation" -> Json.fromString(op)))
    val json = Json.obj(fields*).spaces2

    println(s"$pertTestType metrics for $effectiveName:\n$json")

    try {
      val dir = Paths.get(s"/tmp/$metricsDir")
      Files.createDirectories(dir)
      val metricsFile = dir.resolve(s"$effectiveName.json")
      Files.writeString(
        metricsFile,
        json,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
      )
      println(s"Wrote metrics to $metricsFile")
    } catch {
      case e: Exception =>
        println(s"Failed to write metrics file for $effectiveName: ${e.getMessage}")
    }
  }
}
