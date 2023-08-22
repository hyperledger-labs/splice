package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.store.MultiDomainAcsStore.ContractCompanion
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import slick.jdbc.{GetResult, PositionedResult}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
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

  /** Same as [[selectFromAcsTable]], but joins with the store_descriptors table to get the last_ingested_offset.
    * This guarantees that the fetched contracts exist in the given offset,
    * whereas two separate queries (one to fetch the contract and one to fetch the offset) don't guarantee that.
    */
  protected def selectFromAcsTableWithOffset(
      tableName: String,
      storeId: Int,
      where: SQLActionBuilder,
      orderLimit: SQLActionBuilder = sql"",
  ): SQLActionBuilder =
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
       from store_descriptors sd left join #$tableName on sd.id = store_id
       where sd.id = $storeId and (event_number is null OR (
       """ ++ where ++ sql")) " ++ orderLimit).toActionBuilder // handle the case where we have an offset, but no matching row for `where`

  case class AcsStoreRowTemplateWithOffset(
      offset: String,
      row: Option[AcsStoreRowTemplate],
  )

  object AcsStoreRowTemplateWithOffset {
    implicit val GetResultAcsStoreRowTemplateWithOffset
        : GetResult[AcsStoreRowTemplateWithOffset] = { (pp: PositionedResult) =>
      val storeIdFromAcsRow = pp.<<[Option[Int]]
      AcsStoreRowTemplateWithOffset(
        pp.<<,
        storeIdFromAcsRow.map { storeId =>
          AcsStoreRowTemplate(
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
  }

  protected def contractFromRow[C, TCId <: ContractId[_], T](companion: C)(
      row: AcsTables.AcsStoreRowTemplate
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
