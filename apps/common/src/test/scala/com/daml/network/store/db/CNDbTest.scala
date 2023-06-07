package com.daml.network.store.db

import com.daml.network.config.CNDbConfig
import com.digitalasset.canton.config.DbParametersConfig
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.DbStorageSetup.DbBasicConfig
import com.digitalasset.canton.store.db.{DbStorageSetup, DbTest}
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.*
import slick.dbio.SuccessAction
import slick.lifted.{Rep, TableQuery}

import java.util.concurrent.Semaphore
import scala.concurrent.{Future, blocking}

trait CNDbTest extends DbTest { this: Suite =>

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

  protected def resetAllCnAppTables(
      storage: DbStorage
  )(implicit traceContext: TraceContext): Future[Unit] = {
    import storage.api.jdbcProfile.api.*
    logger.info("Resetting all CN app database tables")
    for {
      _ <- storage.update(
        sql"TRUNCATE user_wallet_acs_store, acs_store_template, store_descriptors RESTART IDENTITY CASCADE".asUpdate,
        "resetAllCnAppTables",
      )
    } yield ()
  }
}

trait CNDbTestLock extends BeforeAndAfterAll with NamedLogging { this: Suite =>

  // Note: all app stores in CN use the same `store_descriptors` table.
  // Since test are running in parallel, we manually synchronize the tests to avoid conflicts
  override def beforeAll(): Unit = {
    CNDbTestLock.acquireCNAppStoreLock(
      ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)
    )
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    CNDbTestLock.releaseCNAppStoreLock(
      ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)
    )
  }
}

object CNDbTestLock {

  /** Synchronize access to the tables common to all CN app stores so that tests do not interfere */
  private val accessCNAppStore: Semaphore = new Semaphore(1)

  def acquireCNAppStoreLock(elc: ErrorLoggingContext): Unit = {
    elc.logger.info(s"Acquiring CN app store test lock")(elc.traceContext)
    blocking(accessCNAppStore.acquire())
    elc.logger.info(s"CN app store test lock acquired")(elc.traceContext)
  }

  def releaseCNAppStoreLock(elc: ErrorLoggingContext): Unit = {
    elc.logger.info(s"Releasing CN app store test lock")(elc.traceContext)
    accessCNAppStore.release()
  }
}

/** Run db test for running against postgres */
trait CNPostgresTest extends CNDbTest with CNDbTestLock { this: Suite =>

  override protected def mkDbConfig(basicConfig: DbBasicConfig): CNDbConfig.Postgres =
    CNDbConfig.Postgres(
      basicConfig.toPostgresConfig,
      parameters = DbParametersConfig(unsafeCleanOnValidationError = true),
    )

  override protected def createSetup(): DbStorageSetup =
    DbStorageSetup.postgres(loggerFactory, migrationMode, mkDbConfig)
}
