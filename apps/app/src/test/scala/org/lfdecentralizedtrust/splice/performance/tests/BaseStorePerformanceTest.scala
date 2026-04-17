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

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}

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

  protected def initializeStorage(): DbStorage = {
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
    // Suppress Flyway ClassPathScanner warnings about unloadable test jars
    org.slf4j.LoggerFactory
      .getLogger("org.flywaydb.core.internal.scanner.classpath.ClassPathScanner")
      .asInstanceOf[ch.qos.logback.classic.Logger]
      .setLevel(ch.qos.logback.classic.Level.ERROR)

    new DbMigrations(storage.dbConfig, false, timeouts, loggerFactory)
      .migrateDatabase()
      .map(_ => storage)
      .getOrElse(throw new RuntimeException("Failed to run migrations."))
      .onShutdown(throw new IllegalStateException("Shutdown should not be happening here"))
  }

  /** Load and parse all transactions in memory so that reading doesn't bottleneck. */
  protected def loadTxsFromDump(): Seq[TreeUpdateWithMigrationId] = {
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
}
