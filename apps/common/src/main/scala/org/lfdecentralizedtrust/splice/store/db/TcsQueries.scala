// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.{
  AcsStoreId,
  SelectFromAcsTableResult,
  SelectFromAcsTableWithStateResult,
}
import slick.dbio.Effect
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder
import slick.jdbc.{GetResult, PositionedResult}
import slick.sql.SqlStreamingAction

trait TcsQueries extends AcsQueries {

  implicit val GetResultSelectFromTcsTableRangeResult
      : GetResult[TcsQueries.SelectFromTcsTableRangeResult] =
    GetResult { (prs: PositionedResult) =>
      val withStateRow = prs.<<[SelectFromAcsTableWithStateResult]
      val archivedAtMicros = prs.<<[Option[Long]]
      TcsQueries.SelectFromTcsTableRangeResult(
        withStateRow,
        archivedAtMicros.map(CantonTimestamp.assertFromLong),
      )
    }

  /** Builds a UNION ALL query across live and archive tables for point-in-time queries.
    * Live table: contracts created at or before asOf (still active).
    * Archive table: contracts created at or before asOf and archived after asOf.
    */
  private def tcsUnionAll[C, TCid <: ContractId[?], T](
      columns: String,
      acsTableName: String,
      archiveTableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      asOf: CantonTimestamp,
      additionalWhere: SQLActionBuilder,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): SQLActionBuilder = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
    (sql"""(
       select #$columns
       from #$acsTableName acs
       where acs.store_id = $storeId
         and acs.migration_id = $migrationId
         and acs.package_name = ${packageQualifiedName.packageName}
         and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
         and acs.created_at <= $asOf
         """ ++ additionalWhere ++ sql"""
       )
       UNION ALL
       (
       select #$columns
       from #$archiveTableName acs
       where acs.store_id = $storeId
         and acs.migration_id = $migrationId
         and acs.package_name = ${packageQualifiedName.packageName}
         and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
         and acs.created_at <= $asOf
         and acs.archived_at > $asOf
         """ ++ additionalWhere ++ sql"""
       )
       """).toActionBuilder
  }

  protected def selectFromTcsTableAsOf[C, TCid <: ContractId[?], T](
      acsTableName: String,
      archiveTableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      asOf: CantonTimestamp,
      additionalWhere: SQLActionBuilder = sql"",
  )(implicit companionClass: ContractCompanion[C, TCid, T]) = {
    tcsUnionAll(
      SelectFromAcsTableResult.sqlColumnsCommaSeparated(),
      acsTableName,
      archiveTableName,
      storeId,
      migrationId,
      companion,
      asOf,
      additionalWhere,
    ).as[SelectFromAcsTableResult]
  }

  protected def selectFromTcsTableWithStateAsOf[C, TCid <: ContractId[?], T](
      acsTableName: String,
      archiveTableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      asOf: CantonTimestamp,
      additionalWhere: SQLActionBuilder = sql"",
  )(implicit companionClass: ContractCompanion[C, TCid, T]): SqlStreamingAction[Vector[
    SelectFromAcsTableWithStateResult
  ], SelectFromAcsTableWithStateResult, Effect.Read] = {
    tcsUnionAll(
      SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated(),
      acsTableName,
      archiveTableName,
      storeId,
      migrationId,
      companion,
      asOf,
      additionalWhere,
    ).as[SelectFromAcsTableWithStateResult]
  }

  /** Builds a UNION ALL query across live and archive tables for range queries.
    * Returns all contracts whose activeness interval intersects with [lowerBoundIncl, upperBoundIncl],
    * together with their archived_at timestamp (None for still-active contracts).
    */
  protected def selectFromTcsTableWithStateActiveWithin[C, TCid <: ContractId[?], T](
      acsTableName: String,
      archiveTableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      lowerBoundIncl: CantonTimestamp,
      upperBoundIncl: CantonTimestamp,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): SqlStreamingAction[Vector[
    TcsQueries.SelectFromTcsTableRangeResult
  ], TcsQueries.SelectFromTcsTableRangeResult, Effect.Read] = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
    val columns = SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated()
    sql"""(
       select #$columns, null::bigint as archived_at
       from #$acsTableName acs
       where acs.store_id = $storeId
         and acs.migration_id = $migrationId
         and acs.package_name = ${packageQualifiedName.packageName}
         and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
         and acs.created_at <= $upperBoundIncl
       )
       UNION ALL
       (
       select #$columns, acs.archived_at
       from #$archiveTableName acs
       where acs.store_id = $storeId
         and acs.migration_id = $migrationId
         and acs.package_name = ${packageQualifiedName.packageName}
         and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
         and acs.created_at <= $upperBoundIncl
         and acs.archived_at > $lowerBoundIncl
       )
       """.toActionBuilder.as[TcsQueries.SelectFromTcsTableRangeResult]
  }
}

object TcsQueries {
  case class SelectFromTcsTableRangeResult(
      withStateRow: SelectFromAcsTableWithStateResult,
      archivedAt: Option[CantonTimestamp],
  )
}
