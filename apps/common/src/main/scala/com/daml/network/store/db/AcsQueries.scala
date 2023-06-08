package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.store.MultiDomainAcsStore.ContractCompanion
import com.daml.network.store.{HardLimit, Limit, PageLimit}
import com.daml.network.util.{Contract, TemplateJsonDecoder}

trait AcsQueries {

  // TODO (#5548): avoid string interpolation
  protected def selectFromAcsTable(tableName: String): String =
    s"""
       |select store_id,
       |  event_number,
       |  contract_id,
       |  template_id,
       |  create_arguments,
       |  contract_metadata_created_at,
       |  contract_metadata_contract_key_hash,
       |  contract_metadata_driver_internal,
       |  contract_expires_at
       |from $tableName
       |""".stripMargin

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

  protected def sqlLimit(limit: Limit): Long = {
    limit match {
      case HardLimit(limit) => limit + 1
      case PageLimit(limit) => limit
    }
  }

}
