package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.DbConfig.Postgres
import com.digitalasset.canton.config.DbParametersConfig
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.DbStorageSetup.DbBasicConfig
import com.digitalasset.canton.store.db.{DbStorageSetup, DbTest}
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.*
import org.scalatest.exceptions.TestFailedException
import slick.dbio.SuccessAction
import slick.lifted.{Rep, TableQuery}

import java.net.ServerSocket
import scala.concurrent.duration.DurationInt
import scala.util.Try

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

  // TODO(tech-debt): Remove this once we have identified the source of timeouts.
  private def debugPrintPgActivity()(implicit traceContext: TraceContext) = {
    import storage.api.jdbcProfile.api.*
    sql"""SELECT datname, pid, application_name, client_addr, client_port, wait_event, state, query
          FROM pg_stat_activity"""
      .as[(String, String, String, String, String, String, String, String)]
      .map { rows =>
        logger.debug(s"pg_stat_activity:\n  ${rows.mkString("\n  ")}")
      }
  }

  protected def resetAllAppTables(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    import storage.api.jdbcProfile.api.*
    logger.info("Resetting all Splice app database tables")
    for {
      _ <- storage.queryAndUpdate(
        for {
          _ <- debugPrintPgActivity()
          _ <- sql"""TRUNCATE
                user_wallet_acs_store,
                user_wallet_acs_interface_views,
                user_wallet_txlog_store,
                scan_acs_store,
                scan_txlog_store,
                sv_acs_store,
                dso_acs_store,
                dso_txlog_store,
                acs_store_template,
                txlog_store_template,
                interface_views_template,
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
                acs_snapshot,
                scan_verdict_store,
                scan_verdict_transaction_view_store
            RESTART IDENTITY CASCADE""".asUpdate
          _ <- debugPrintPgActivity()
        } yield (),
        "resetAllAppTables",
      )
    } yield {
      logger.info("Resetting all Splice app database tables complete")
      ()
    }
  }

  private var dbLockSocket: Option[ServerSocket] = None

  // Note: all app stores in Splice use the same `store_descriptors` table.
  // Since test are running in parallel, we manually synchronize the tests to avoid conflicts.
  // Since tests might be running in different JVM instances, we are using a socket for synchronization.
  override def beforeAll(): Unit = {
    val dbLockPort: Int = 54321
    implicit val tc: TraceContext = TraceContext.empty
    logger.info("Acquiring SpliceDbTest lock")
    // Needs to be long enough to allow all other concurrently started tests to finish,
    // we therefore use a time roughly equal to the expected maximal duration of the entire CI job.
    val lockTimeout = 20.minutes
    dbLockSocket = BaseTest.eventually(lockTimeout)(
      Try(new ServerSocket(dbLockPort))
        .fold(
          e => {
            logger.debug(s"Acquiring SpliceDbTest lock: port $dbLockPort is in use")
            throw new TestFailedException(
              s"Failed to acquire lock within timeout ($lockTimeout). " +
                "We start many tests suites in parallel but wait for the lock before actually running test in this suite. " +
                "Either increase the timeout, or reduce the number of test suites running in the same CI job.",
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
    super.afterAll()
    logger.info("Releasing SpliceDbTest lock")
    dbLockSocket.foreach(_.close())
  }
}

/** Run db test for running against postgres */
trait SplicePostgresTest extends SpliceDbTest { this: Suite =>

  override protected def mkDbConfig(
      basicConfig: DbBasicConfig
  ): com.digitalasset.canton.config.DbConfig.Postgres =
    Postgres(
      basicConfig.toPostgresConfig,
      parameters = DbParametersConfig(unsafeCleanOnValidationError = true),
    )

  override protected def createSetup(): DbStorageSetup =
    DbStorageSetup.postgres(loggerFactory, migrationMode, mkDbConfig)
}
