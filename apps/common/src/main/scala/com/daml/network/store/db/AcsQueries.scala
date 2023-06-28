package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.store.MultiDomainAcsStore.ContractCompanion
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
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
