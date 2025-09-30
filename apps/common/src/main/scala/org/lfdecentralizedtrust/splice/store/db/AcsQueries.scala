// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{ContractCompanion, ContractState}
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.{
  AcsStoreId,
  SelectFromAcsTableResult,
  SelectFromAcsTableWithStateResult,
  SelectFromContractStateResult,
}
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  LegacyOffset,
  QualifiedName,
  TemplateJsonDecoder,
}
import slick.jdbc.{GetResult, PositionedResult, SetParameter}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.resource.DbStorage.SQLActionBuilderChain
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import io.circe.Json
import io.grpc.Status
import slick.jdbc.canton.SQLActionBuilder
import com.google.protobuf.ByteString
import scalaz.{@@, Tag}

trait AcsQueries extends AcsJdbcTypes {

  /** @param tableName Must be SQL-safe, as it needs to be interpolated unsafely.
    *                  This is fine, as all calls to this method should use static string constants.
    */
  protected def selectFromAcsTable(
      tableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()}
       from #$tableName acs
       where acs.store_id = $storeId and acs.migration_id = $migrationId and """ ++ where ++ sql"""
       """ ++ orderLimit).toActionBuilder.as[AcsQueries.SelectFromAcsTableResult]

  implicit val GetResultSelectFromAcsTable: GetResult[AcsQueries.SelectFromAcsTableResult] =
    GetResult { prs =>
      import prs.*
      (AcsQueries.SelectFromAcsTableResult.apply _).tupled(
        (
          <<[AcsStoreId],
          <<[Long],
          <<[Long],
          <<[ContractId[Any]],
          <<[String],
          <<[QualifiedName],
          <<[Json],
          <<[Array[Byte]],
          <<[Timestamp],
          <<[Option[Timestamp]],
        )
      )
    }

  /** Similar to [[selectFromAcsTable]], but also returns the contract state (i.e., the domain to which a contract is currently assigned) */
  protected def selectFromAcsTableWithState(
      tableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select #${SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated()}
       from #$tableName acs
       where acs.store_id = $storeId and acs.migration_id = $migrationId and """ ++ where ++ sql"""
       """ ++ orderLimit).toActionBuilder.as[AcsQueries.SelectFromAcsTableWithStateResult]

  implicit val GetResultSelectFromContractStateResult
      : GetResult[AcsQueries.SelectFromContractStateResult] =
    GetResult { prs =>
      AcsQueries.SelectFromContractStateResult(
        prs.<<[Long],
        prs.<<[Option[SynchronizerId]],
        prs.<<[Long],
        prs.<<[Option[SynchronizerId]],
        prs.<<[Option[SynchronizerId]],
        prs.<<[Option[PartyId]],
        prs.<<[Option[String]],
      )
    }

  implicit val GetResultSelectFromAcsTableWithState
      : GetResult[AcsQueries.SelectFromAcsTableWithStateResult] =
    GetResult { prs =>
      val acsRow = prs.<<[AcsQueries.SelectFromAcsTableResult]
      val stateRow = prs.<<[AcsQueries.SelectFromContractStateResult]
      AcsQueries.SelectFromAcsTableWithStateResult(acsRow, stateRow)
    }

  /** Same as [[selectFromAcsTable]], but joins with the store_descriptors and store_last_ingested_offsets table to get the last_ingested_offset.
    * This guarantees that the fetched contracts exist in the given offset,
    * whereas two separate queries (one to fetch the contract and one to fetch the offset) don't guarantee that.
    */
  protected def selectFromAcsTableWithOffset(
      tableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select
         acs.store_id,
         acs.migration_id,
         o.last_ingested_offset,
         event_number,
         contract_id,
         template_id_package_id,
         template_id_qualified_name,
         create_arguments,
         created_event_blob,
         created_at,
         contract_expires_at
       from store_descriptors sd
           left join store_last_ingested_offsets o
               on sd.id = o.store_id
           left join #$tableName acs
               on o.store_id = acs.store_id
               and o.migration_id = acs.migration_id
               and """ ++ where ++ sql"""
       where sd.id = $storeId and o.migration_id = $migrationId
       """ ++ orderLimit).toActionBuilder
      .as[AcsQueries.SelectFromAcsTableResultWithOffset]

  implicit val GetResultSelectFromAcsTableResultWithOffset
      : GetResult[AcsQueries.SelectFromAcsTableResultWithOffset] = { (pp: PositionedResult) =>
    val storeIdFromAcsRow = pp.<<[Option[AcsStoreId]]
    val migrationIdFromAcsRow = pp.<<[Option[Long]]
    AcsQueries.SelectFromAcsTableResultWithOffset(
      LegacyOffset.Api.assertFromStringToLong(pp.<<[String]),
      for {
        storeId <- storeIdFromAcsRow
        migration_id <- migrationIdFromAcsRow
      } yield AcsQueries.SelectFromAcsTableResult(
        storeId,
        migration_id,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
        pp.<<,
      ),
    )
  }

  /** Same as [[selectFromAcsTableWithOffset]], but also includes the contract state.
    */
  protected def selectFromAcsTableWithStateAndOffset(
      tableName: String,
      storeId: AcsStoreId,
      migrationId: Long,
      where: SQLActionBuilder = sql"true",
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select
         acs.store_id,
         acs.migration_id,
         o.last_ingested_offset,
         acs.event_number,
         acs.contract_id,
         acs.template_id_package_id,
         acs.template_id_qualified_name,
         acs.create_arguments,
         acs.created_event_blob,
         acs.created_at,
         acs.contract_expires_at,
         acs.state_number,
         acs.assigned_domain,
         acs.reassignment_counter,
         acs.reassignment_target_domain,
         acs.reassignment_source_domain,
         acs.reassignment_submitter,
         acs.reassignment_unassign_id
       from store_descriptors sd
           left join store_last_ingested_offsets o
               on sd.id = o.store_id
           left join #$tableName acs
               on o.store_id = acs.store_id
               and o.migration_id = acs.migration_id
               and """ ++ where ++ sql"""
       where sd.id = $storeId and o.migration_id = $migrationId
       """ ++ orderLimit).toActionBuilder
      .as[AcsQueries.SelectFromAcsTableResultWithStateAndOffset]

  implicit val GetResultSelectFromAcsTableResultWithStateOffset
      : GetResult[AcsQueries.SelectFromAcsTableResultWithStateAndOffset] = {
    (pp: PositionedResult) =>
      val storeIdFromAcsRow = pp.<<[Option[AcsStoreId]]
      val migrationIdFromAcsRow = pp.<<[Option[Long]]
      AcsQueries.SelectFromAcsTableResultWithStateAndOffset(
        LegacyOffset.Api.assertFromStringToLong(pp.<<[String]),
        for {
          storeId <- storeIdFromAcsRow
          migrationId <- migrationIdFromAcsRow
        } yield AcsQueries.SelectFromAcsTableWithStateResult(
          AcsQueries.SelectFromAcsTableResult(
            storeId,
            migrationId,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
          ),
          AcsQueries.SelectFromContractStateResult(
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
          ),
        ),
      )
  }

  /** Constructions like `seq.mkString("(", ",", ")")` are dangerous because they can lead to SQL injection.
    * Prefer using this instead, or [[inClause]] when in a `WHERE x IN`.
    */
  protected def sqlCommaSeparated[V](
      seq: Iterable[V]
  )(implicit
      sp: SetParameter[V]
  ): SQLActionBuilder = {
    seq
      .map(v => sql"$v")
      .reduceOption { (acc, next) =>
        (acc ++ sql"," ++ next).toActionBuilder
      }
      .getOrElse(sql"")
  }

  protected def inClause[V: SetParameter](seq: Iterable[V]): SQLActionBuilderChain =
    sql"(" ++ sqlCommaSeparated(seq) ++ sql")"

  protected def contractFromRow[C, TCId <: ContractId[_], T](companion: C)(
      row: AcsQueries.SelectFromAcsTableResult
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      decoder: TemplateJsonDecoder,
  ): Contract[TCId, T] = {
    row.toContract(companion)
  }

  protected def assignedContractFromRow[C, TCid <: ContractId[_], T](companion: C)(
      row: SelectFromAcsTableWithStateResult
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      templateJsonDecoder: TemplateJsonDecoder,
  ): AssignedContract[TCid, T] = {
    val contract = contractFromRow(companion)(row.acsRow)
    row.stateRow.assignedDomain match {
      case Some(domain) => AssignedContract(contract, domain)
      case None =>
        throw Status.FAILED_PRECONDITION
          .withDescription(
            s"Cannot read contract ${contract.contractId} as AssignedContract, it is in flight with ${row.stateRow}"
          )
          .asRuntimeException()
    }
  }

  protected def contractWithStateFromRow[C, TCid <: ContractId[_], T](companion: C)(
      row: SelectFromAcsTableWithStateResult
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      templateJsonDecoder: TemplateJsonDecoder,
  ): ContractWithState[TCid, T] = {
    val state = contractStateFromRow(row.stateRow)
    val contract = contractFromRow(companion)(row.acsRow)
    ContractWithState(contract, state)
  }

  protected def contractStateFromRow(
      row: SelectFromContractStateResult
  ): ContractState = {
    row.assignedDomain.fold[ContractState](ContractState.InFlight)(id => ContractState.Assigned(id))
  }
}

object AcsQueries {

  sealed trait AcsStoreIdTag
  type AcsStoreId = Int @@ AcsStoreIdTag
  val AcsStoreId = Tag.of[AcsStoreIdTag]

  case class SelectFromAcsTableResult(
      storeId: AcsStoreId,
      migrationId: Long,
      eventNumber: Long,
      contractId: ContractId[Any],
      templateIdPackageId: String,
      templateIdQualifiedName: QualifiedName,
      createArguments: Json,
      createdEventBlob: Array[Byte],
      createdAt: Timestamp,
      contractExpiresAt: Option[Timestamp],
  ) {
    def toContract[C, TCId <: ContractId[_], T](companion: C)(implicit
        companionClass: ContractCompanion[C, TCId, T],
        decoder: TemplateJsonDecoder,
    ): Contract[TCId, T] = {
      companionClass
        .fromJson(companion)(
          new Identifier(
            templateIdPackageId,
            templateIdQualifiedName.moduleName,
            templateIdQualifiedName.entityName,
          ),
          contractId.contractId,
          createArguments,
          ByteString.copyFrom(createdEventBlob),
          createdAt.toInstant,
        )
        .fold(
          _ =>
            throw io.grpc.Status.NOT_FOUND
              .withDescription("Contract not found.")
              .asRuntimeException(),
          identity,
        )
    }
  }

  object SelectFromAcsTableResult {
    def sqlColumnsCommaSeparated(qualifier: String = "") =
      s"""${qualifier}store_id,
          ${qualifier}migration_id,
          ${qualifier}event_number,
          ${qualifier}contract_id,
          ${qualifier}template_id_package_id,
          ${qualifier}template_id_qualified_name,
          ${qualifier}create_arguments,
          ${qualifier}created_event_blob,
          ${qualifier}created_at,
          ${qualifier}contract_expires_at"""
  }

  case class SelectFromContractStateResult(
      stateNumber: Long,
      assignedDomain: Option[SynchronizerId],
      reassignmentCounter: Long,
      reassignmentTargetDomain: Option[SynchronizerId],
      reassignmentSourceDomain: Option[SynchronizerId],
      reassignmentSubmitter: Option[PartyId],
      reassignmentUnassignId: Option[String],
  )

  case class SelectFromAcsTableWithStateResult(
      acsRow: SelectFromAcsTableResult,
      stateRow: SelectFromContractStateResult,
  )

  object SelectFromAcsTableWithStateResult {
    def sqlColumnsCommaSeparated(qualifier: String = "") =
      SelectFromAcsTableResult.sqlColumnsCommaSeparated(
        qualifier
      ) + "," + stateColumnsCommaSeparated(qualifier)

    def stateColumnsCommaSeparated(qualifier: String = "") = s"""
        ${qualifier}state_number,
        ${qualifier}assigned_domain,
        ${qualifier}reassignment_counter,
        ${qualifier}reassignment_target_domain,
        ${qualifier}reassignment_source_domain,
        ${qualifier}reassignment_submitter,
        ${qualifier}reassignment_unassign_id
      """
  }

  case class SelectFromAcsTableResultWithOffset(
      offset: Long,
      row: Option[SelectFromAcsTableResult],
  )

  case class SelectFromAcsTableResultWithStateAndOffset(
      offset: Long,
      row: Option[SelectFromAcsTableWithStateResult],
  )
}
