package org.lfdecentralizedtrust.splice.store.db

import org.lfdecentralizedtrust.splice.config.SpliceDbConfig
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.DbParametersConfig
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.DbStorageSetup.DbBasicConfig
import com.digitalasset.canton.store.db.{DbStorageSetup, DbTest}
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.*
import org.scalatest.exceptions.TestFailedException
import slick.dbio.SuccessAction
import slick.lifted.{Rep, TableQuery}

import java.net.ServerSocket
import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.duration.DurationInt

trait SpliceDbTest extends DbTest with BeforeAndAfterAll { this: Suite =>

  // Inserts the given row into the given table, unless the row already exists.
  // Note: Update actions must be idempotent. To avoid manually constructing a 'INSERT ... ON UPDATE DO NOTHING' statement,
  // we first check whether the target row exists in a separate statement. This is good enough for tests that perform
  // all database operations sequentially.
  protected def insertRowIfNotExists[E <: slick.lifted.AbstractTable[_]](
      table: TableQuery[E]
  )(
      filter: E => Rep[Boolean],
      row: E#TableElementType,
  ) = {
    import storage.api.jdbcProfile.api.*
    for {
      exists <- table
        .filter(filter)
        .exists
        .result
      _ <-
        if (!exists) { table += row }
        else SuccessAction(())
    } yield ()
  }

  protected def resetAllAppTables(
      storage: DbStorage
  )(implicit traceContext: TraceContext): Future[Unit] = {
    import storage.api.jdbcProfile.api.*
    logger.info("Resetting all Splice app database tables")
    for {
      _ <- storage.update(
        sql"""TRUNCATE
                user_wallet_acs_store,
                user_wallet_txlog_store,
                scan_acs_store,
                scan_txlog_store,
                sv_acs_store,
                dso_acs_store,
                dso_txlog_store,
                acs_store_template,
                txlog_store_template,
                incomplete_reassignments,
                store_descriptors,
                store_last_ingested_offsets,
                round_totals,
                round_party_totals,
                update_history_descriptors,
                update_history_last_ingested_offsets,
                update_history_transactions,
                update_history_exercises,
                update_history_creates,
                update_history_assignments,
                update_history_unassignments,
                update_history_backfilling,
                acs_snapshot_data,
                acs_snapshot
            RESTART IDENTITY CASCADE""".asUpdate,
        "resetAllAppTables",
      )
    } yield ()
  }

  private var dbLockSocket: Option[ServerSocket] = None

  // Note: all app stores in Splice use the same `store_descriptors` table.
  // Since test are running in parallel, we manually synchronize the tests to avoid conflicts.
  // Since tests might be running in different JVM instances, we are using a socket for synchronization.
  override def beforeAll(): Unit = {
    val dbLockPort: Int = 54321
    implicit val tc: TraceContext = TraceContext.empty
    logger.info("Acquiring SpliceDbTest lock")
    val lockTimeout = 10.minutes // expectation: Db tests won't take longer than 5m
    dbLockSocket = BaseTest.eventually(lockTimeout)(
      Try(new ServerSocket(dbLockPort))
        .fold(
          e => {
            logger.debug(s"Acquiring SpliceDbTest lock: port $dbLockPort is in use")
            throw new TestFailedException(
              s"Failed to acquire lock within timeout ($lockTimeout).",
              e,
              0,
            )
          },
          Some(_),
        )
    )
    logger.info("SpliceDbTest lock acquired")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    implicit val tc: TraceContext = TraceContext.empty
    logger.info("Releasing SpliceDbTest lock")
    dbLockSocket.foreach(_.close())
    super.afterAll()
  }
}

/** Run db test for running against postgres */
trait SplicePostgresTest extends SpliceDbTest { this: Suite =>

  override protected def mkDbConfig(basicConfig: DbBasicConfig): SpliceDbConfig.Postgres =
    SpliceDbConfig.Postgres(
      basicConfig.toPostgresConfig,
      parameters = DbParametersConfig(unsafeCleanOnValidationError = true),
    )

  override protected def createSetup(): DbStorageSetup =
    DbStorageSetup.postgres(loggerFactory, migrationMode, mkDbConfig)
}
