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
import org.apache.pekko.Done
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

import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}

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

  def run(): Future[Unit] = {
    val storage = initializeStorage()
    val store = mkStore(storage)
    TraceContext
      .withNewTraceContext(this.getClass.getName) { implicit tc =>
        for {
          _ <- store.ingestionSink.initialize()
          _ <- sanityCheckTables(storage) { count =>
            Option.when(count != 0)(
              s"Expected table to be empty before ingestion, but found $count rows."
            )
          }
          txs = loadTxsFromDump()
          _ <- ingestAll(store, txs)
          _ <- sanityCheckTables(storage) { count =>
            Option.when(count == 0)(
              s"Expected table to be non-empty after ingestion, but no rows were inserted."
            )
          }
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
        CompactJsonScanHttpEncodings.httpToLapiTransaction(update, index.toLong)
    }
  }

  private def ingestAll(store: Store, txs: Seq[TreeUpdateWithMigrationId])(implicit
      tc: TraceContext
  ): Future[Done] = {
    var totalTime = BigDecimal(0)
    var totalItems = 0L
    var totalBatches = 0L
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
        val before = System.nanoTime()
        store.ingestionSink
          .ingestUpdateBatch(NonEmptyList.fromListUnsafe(batch))
          .map { _ =>
            val after = System.nanoTime()
            val duration = after - before
            totalTime += duration
            totalItems += batch.length
            totalBatches += 1
            val avg = totalTime / totalItems
            logger.info(
              f"Ingested batch $index (${batch.length} elements) in $duration ns, average per-item time: $avg%.2f ns over $totalItems records, total time: $totalTime ns"
            )
          }
      })
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
