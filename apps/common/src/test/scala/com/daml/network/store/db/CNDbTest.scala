package com.daml.network.store.db

import com.daml.network.config.CNDbConfig
import com.digitalasset.canton.config.DbParametersConfig
import com.digitalasset.canton.store.db.DbStorageSetup.DbBasicConfig
import com.digitalasset.canton.store.db.{DbStorageSetup, DbTest}
import org.scalatest.*
import slick.dbio.SuccessAction
import slick.lifted.{Rep, TableQuery}

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

}

/** Run db test for running against postgres */
trait CNPostgresTest extends CNDbTest { this: Suite =>

  override protected def mkDbConfig(basicConfig: DbBasicConfig): CNDbConfig.Postgres =
    CNDbConfig.Postgres(
      basicConfig.toPostgresConfig,
      parameters = DbParametersConfig(unsafeCleanOnValidationError = true),
    )

  override protected def createSetup(): DbStorageSetup =
    DbStorageSetup.postgres(loggerFactory, migrationMode, mkDbConfig)
}
