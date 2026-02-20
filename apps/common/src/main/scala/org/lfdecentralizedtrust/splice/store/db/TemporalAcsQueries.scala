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
import slick.sql.SqlStreamingAction

trait TemporalAcsQueries extends AcsQueries {

  /** Builds a UNION ALL query across live and archive tables for point-in-time queries.
    * Live table: contracts created at or before asOf (still active).
    * Archive table: contracts created at or before asOf and archived after asOf.
    */
  private def temporalUnionAll[C, TCid <: ContractId[?], T](
      columns: String,
      acsTableName: String,
      archiveTableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      asOf: CantonTimestamp,
      additionalWhere: SQLActionBuilder,
      orderLimit: SQLActionBuilder,
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
       """ ++ orderLimit).toActionBuilder
  }

  protected def selectFromAcsTableAsOf[C, TCid <: ContractId[?], T](
      acsTableName: String,
      archiveTableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      asOf: CantonTimestamp,
      additionalWhere: SQLActionBuilder = sql"",
      orderLimit: SQLActionBuilder = sql"",
  )(implicit companionClass: ContractCompanion[C, TCid, T]) = {
    temporalUnionAll(
      SelectFromAcsTableResult.sqlColumnsCommaSeparated(),
      acsTableName,
      archiveTableName,
      storeId,
      migrationId,
      companion,
      asOf,
      additionalWhere,
      orderLimit,
    ).as[SelectFromAcsTableResult]
  }

  protected def selectFromAcsTableWithStateAsOf[C, TCid <: ContractId[?], T](
      acsTableName: String,
      archiveTableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      companion: C,
      asOf: CantonTimestamp,
      additionalWhere: SQLActionBuilder = sql"",
      orderLimit: SQLActionBuilder = sql"",
  )(implicit companionClass: ContractCompanion[C, TCid, T]): SqlStreamingAction[Vector[
    SelectFromAcsTableWithStateResult
  ], SelectFromAcsTableWithStateResult, Effect.Read] = {
    temporalUnionAll(
      SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated(),
      acsTableName,
      archiveTableName,
      storeId,
      migrationId,
      companion,
      asOf,
      additionalWhere,
      orderLimit,
    ).as[SelectFromAcsTableWithStateResult]
  }
}
