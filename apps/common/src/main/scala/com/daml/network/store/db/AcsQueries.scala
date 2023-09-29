package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.store.MultiDomainAcsStore.ContractCompanion
import com.daml.network.store.db.AcsTables.TxLogStoreRowTemplate
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.admin.api.client.data.TemplateId
import slick.jdbc.{GetResult, PositionedResult}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import io.circe.Json
import slick.jdbc.canton.SQLActionBuilder

trait AcsQueries extends AcsJdbcTypes {

  /** @param tableName Must be SQL-safe, as it needs to be interpolated unsafely.
    *                  This is fine, as all calls to this method should use static string constants.
    */
  protected def selectFromAcsTable(tableName: String): SQLActionBuilder =
    sql"""
       select store_id,
         event_number,
         contract_id,
         template_id,
         create_arguments,
         contract_metadata_created_at,
         contract_metadata_contract_key_hash,
         contract_metadata_driver_internal,
         contract_expires_at
       from #$tableName
       """.stripMargin

  implicit val GetResultSelectFromAcsTable: GetResult[AcsQueries.SelectFromAcsTableResult] =
    GetResult { prs =>
      import prs.*
      (AcsQueries.SelectFromAcsTableResult.apply _).tupled(
        (
          <<[Int],
          <<[Long],
          <<[ContractId[Any]],
          <<[TemplateId],
          <<[Json],
          <<[Timestamp],
          <<[Option[String]],
          <<[Array[Byte]],
          <<[Option[Timestamp]],
        )
      )
    }

  /** Similar to [[selectFromAcsTable]], but also returns the contract state (i.e., the domain to which a contract is currently assigned) */
  protected def selectFromAcsTableWithState(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select store_id,
         event_number,
         contract_id,
         template_id,
         create_arguments,
         contract_metadata_created_at,
         contract_metadata_contract_key_hash,
         contract_metadata_driver_internal,
         contract_expires_at,
         assigned_domain,
         reassignment_counter,
         reassignment_target_domain,
         reassignment_source_domain,
         reassignment_submitter,
         reassignment_unassign_id
       from #$tableName acs
       where acs.store_id = $storeId and """ ++ where ++ sql"""
       """ ++ orderLimit).toActionBuilder.as[AcsQueries.SelectFromAcsTableWithStateResult]

  implicit val GetResultSelectFromContractStateResult
      : GetResult[AcsQueries.SelectFromContractStateResult] =
    GetResult { prs =>
      AcsQueries.SelectFromContractStateResult(
        prs.<<[Option[String]],
        prs.<<[Long],
        prs.<<[Option[String]],
        prs.<<[Option[String]],
        prs.<<[Option[String]],
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

  /** Same as [[selectFromAcsTable]], but joins with the store_descriptors table to get the last_ingested_offset.
    * This guarantees that the fetched contracts exist in the given offset,
    * whereas two separate queries (one to fetch the contract and one to fetch the offset) don't guarantee that.
    */
  protected def selectFromAcsTableWithOffset(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select
         store_id,
         last_ingested_offset,
         event_number,
         contract_id,
         template_id,
         create_arguments,
         contract_metadata_created_at,
         contract_metadata_contract_key_hash,
         contract_metadata_driver_internal,
         contract_expires_at
       from store_descriptors sd
           left join #$tableName
               on sd.id = store_id
               and """ ++ where ++ sql"""
       where sd.id = $storeId
       """ ++ orderLimit).toActionBuilder
      .as[AcsQueries.SelectFromAcsTableResultWithOffset]

  implicit val GetResultSelectFromAcsTableResultWithOffset
      : GetResult[AcsQueries.SelectFromAcsTableResultWithOffset] = { (pp: PositionedResult) =>
    val storeIdFromAcsRow = pp.<<[Option[Int]]
    AcsQueries.SelectFromAcsTableResultWithOffset(
      pp.<<,
      storeIdFromAcsRow.map { storeId =>
        AcsQueries.SelectFromAcsTableResult(
          storeId,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
          pp.<<,
        )
      },
    )
  }

  /** Same as [[selectFromAcsTableWithOffset]], but also includes the contract state.
    */
  protected def selectFromAcsTableWithStateAndOffset(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder = sql"true",
      orderLimit: SQLActionBuilder = sql"",
  ) =
    (sql"""
       select
         acs.store_id,
         sd.last_ingested_offset,
         acs.event_number,
         acs.contract_id,
         acs.template_id,
         acs.create_arguments,
         acs.contract_metadata_created_at,
         acs.contract_metadata_contract_key_hash,
         acs.contract_metadata_driver_internal,
         acs.contract_expires_at,
         acs.assigned_domain,
         acs.reassignment_counter,
         acs.reassignment_target_domain,
         acs.reassignment_source_domain,
         acs.reassignment_submitter,
         acs.reassignment_unassign_id
       from store_descriptors sd
           left join #$tableName acs
               on sd.id = acs.store_id
               and """ ++ where ++ sql"""
       where sd.id = $storeId
       """ ++ orderLimit).toActionBuilder
      .as[AcsQueries.SelectFromAcsTableResultWithStateAndOffset]

  implicit val GetResultSelectFromAcsTableResultWithStateOffset
      : GetResult[AcsQueries.SelectFromAcsTableResultWithStateAndOffset] = {
    (pp: PositionedResult) =>
      val storeIdFromAcsRow = pp.<<[Option[Int]]
      AcsQueries.SelectFromAcsTableResultWithStateAndOffset(
        pp.<<,
        storeIdFromAcsRow.map { storeId =>
          AcsQueries.SelectFromAcsTableWithStateResult(
            AcsQueries.SelectFromAcsTableResult(
              storeId,
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
            ),
          )
        },
      )
  }

  /** Same as [[selectFromAcsTableWithOffset]], but for tx log tables.
    */
  protected def selectFromTxLogTableWithOffset(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ): SQLActionBuilder =
    (sql"""
       select
         store_id,
         last_ingested_offset,
         entry_number,
         event_id,
         domain_id,
         acs_contract_id
       from store_descriptors sd
           left join #$tableName
               on sd.id = store_id
               and """ ++ where ++ sql"""
       where sd.id = $storeId
       """ ++ orderLimit).toActionBuilder

  case class TxLogStoreRowTemplateWithOffset(
      offset: String,
      row: Option[TxLogStoreRowTemplate],
  )

  object TxLogStoreRowTemplateWithOffset {
    implicit val GetResultTxLogStoreRowTemplateWithOffset
        : GetResult[TxLogStoreRowTemplateWithOffset] = { (pp: PositionedResult) =>
      val storeIdFromTxLogRow = pp.<<[Option[Int]]
      TxLogStoreRowTemplateWithOffset(
        pp.<<,
        storeIdFromTxLogRow.map { storeId =>
          TxLogStoreRowTemplate(
            storeId,
            pp.<<,
            pp.<<,
            pp.<<,
            pp.<<,
          )
        },
      )
    }
  }

  protected def contractFromRow[C, TCId <: ContractId[_], T](companion: C)(
      row: AcsQueries.SelectFromAcsTableResult
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      decoder: TemplateJsonDecoder,
  ): Contract[TCId, T] = {
    companionClass
      .fromJson(companion)(
        row.templateId,
        row.contractId.contractId,
        row.createArguments,
        row.contractMetadataCreatedAt.toInstant,
        row.contractMetadataContractKeyHash,
        row.contractMetadataDriverInternal,
      )
      .fold(
        err => throw new IllegalStateException(s"Stored a contract that cannot be decoded: $err"),
        identity,
      )
  }

}

object AcsQueries {
  case class SelectFromAcsTableResult(
      storeId: Int,
      eventNumber: Long,
      contractId: ContractId[Any],
      templateId: TemplateId,
      createArguments: Json,
      contractMetadataCreatedAt: Timestamp,
      contractMetadataContractKeyHash: Option[String] = None,
      contractMetadataDriverInternal: Array[Byte],
      contractExpiresAt: Option[Timestamp] = None,
  )

  case class SelectFromContractStateResult(
      assignedDomain: Option[String],
      reassignmentCounter: Long,
      reassignmentTargetDomain: Option[String],
      reassignmentSourceDomain: Option[String],
      reassignmentSubmitter: Option[String],
      reassignmentUnassignId: Option[String],
  )

  case class SelectFromAcsTableWithStateResult(
      acsRow: SelectFromAcsTableResult,
      stateRow: SelectFromContractStateResult,
  )

  case class SelectFromAcsTableResultWithOffset(
      offset: String,
      row: Option[SelectFromAcsTableResult],
  )

  case class SelectFromAcsTableResultWithStateAndOffset(
      offset: String,
      row: Option[SelectFromAcsTableWithStateResult],
  )
}
