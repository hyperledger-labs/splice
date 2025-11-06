// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.DbAction
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.validator.store.ValidatorInternalStore
import org.lfdecentralizedtrust.splice.validator.store.db.ValidatorInternalTables.{
  ScanConfigRow,
  tableName,
}
import slick.jdbc.PositionedParameters

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown, UnlessShutdown}
import com.digitalasset.canton.logging.ErrorLoggingContext
import slick.jdbc.GetResult

import com.digitalasset.canton.resource.DbStorage.Profile.Postgres

class DbValidatorInternalStore(
    storage: DbStorage,
    implicit val loggingContext: ErrorLoggingContext,
    implicit val closeContext: CloseContext,
)(implicit val ec: ExecutionContext)
    extends ValidatorInternalStore {

  import storage.api.*

  private val setScanConfigRow: PositionedParameters => ScanConfigRow => Unit = pp =>
    row => {
      pp.setString(row.svName)
      pp.setString(row.scanUrl)
      pp.setInt(row.restart_count)
    }

  private implicit val getScanConfigRow: GetResult[ScanConfigRow] = GetResult { r =>
    ScanConfigRow(
      svName = r.nextString(),
      scanUrl = r.nextString(),
      restart_count = r.nextInt(),
    )
  }

  private def toFuture[A](
      fus: com.digitalasset.canton.lifecycle.FutureUnlessShutdown[A]
  ): Future[A] =
    fus.unwrap.map {
      case UnlessShutdown.Outcome(value) => value
      case UnlessShutdown.AbortedDueToShutdown =>
        throw new Exception("Operation aborted due to shutdown.")
    }

  override def setScanConfigs(rows: Seq[ScanConfigRow])(implicit tc: TraceContext): Future[Unit] = {

    val insertSql =
      s"""INSERT INTO $tableName(sv_name, scan_url, restart_count)
         |VALUES (?, ?, ?)
         |ON CONFLICT (scan_url, sv_name, restart_count) DO NOTHING
         |""".stripMargin

    val bulkAction: DbAction.All[Unit] = DbStorage.bulkOperation_(
      insertSql,
      rows,
      storage.profile,
    )(setScanConfigRow)

    val transactionalAction: DBIOAction[Unit, slick.dbio.NoStream, slick.dbio.Effect.All] =
      bulkAction.transactionally

    val futureUnlessShutdown: FutureUnlessShutdown[Unit] = storage.profile match {
      case _: Postgres =>
        storage.queryAndUpdate(transactionalAction, s"bulk-upsert-$tableName-configs")
      case other =>
        throw new UnsupportedOperationException(
          s"DbValidatorInternalStore is only supported for Postgres, but found $other"
        )
    }
    toFuture(futureUnlessShutdown)
  }

  override def getScanConfigs()(implicit tc: TraceContext): Future[Seq[ScanConfigRow]] = {
    val action: DbAction.ReadTransactional[Vector[ScanConfigRow]] =
      sql"""
        SELECT sv_name, scan_url, restart_count
        FROM #$tableName
        WHERE restart_count = (
          SELECT MAX(restart_count) FROM #$tableName
        )
        ORDER BY sv_name ASC
      """.as[ScanConfigRow]

    toFuture(
      storage.query(action, s"get-all-active-$tableName-configs")
    ).map(_.toSeq)
  }
}
