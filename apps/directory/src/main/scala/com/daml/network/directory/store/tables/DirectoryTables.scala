package com.daml.network.directory.store.tables

import com.daml.lf.data.Time.Timestamp
import com.daml.lf.value.Value.ContractId
import com.daml.network.store.tables.AcsTables
import com.digitalasset.canton.admin.api.client.data.TemplateId
import io.circe.Json

object DirectoryTables extends AcsTables {
  import profile.api.*

  lazy val schema: profile.SchemaDescription = acsBaseSchema ++ DirectoryAcsStore.schema

  case class DirectoryAcsStoreRow(
      storeId: Int,
      eventNumber: Long,
      contractId: ContractId,
      templateId: TemplateId,
      createArguments: Json,
      contractMetadataCreatedAt: Timestamp,
      contractMetadataContractKeyHash: Option[String] = None,
      contractMetadataDriverInternal: Array[Byte],
      contractExpiresAt: Option[Timestamp] = None,
      directoryInstallUser: Option[String] = None,
      directoryEntryName: Option[String] = None,
      directoryEntryOwner: Option[String] = None,
      subscriptionContextContractId: Option[ContractId] = None,
      subscriptionNextPaymentDueAt: Option[Timestamp] = None,
  )

  class DirectoryAcsStore(_tableTag: Tag)
      extends profile.api.Table[DirectoryAcsStoreRow](_tableTag, "directory_acs_store") {
    def * = (
      storeId,
      eventNumber,
      contractId,
      templateId,
      createArguments,
      contractMetadataCreatedAt,
      contractMetadataContractKeyHash,
      contractMetadataDriverInternal,
      contractExpiresAt,
      directoryInstallUser,
      directoryEntryName,
      directoryEntryOwner,
      subscriptionContextContractId,
      subscriptionNextPaymentDueAt,
    ).<>(DirectoryAcsStoreRow.tupled, DirectoryAcsStoreRow.unapply)

    val storeId: Rep[Int] = column[Int]("store_id")

    val eventNumber: Rep[Long] = column[Long]("event_number", O.AutoInc, O.PrimaryKey)

    val contractId: Rep[ContractId] = column[ContractId]("contract_id")

    val templateId: Rep[TemplateId] = column[TemplateId]("template_id")

    val createArguments: Rep[Json] = column[Json]("create_arguments")

    val contractMetadataCreatedAt: Rep[Timestamp] =
      column[Timestamp]("contract_metadata_created_at")

    val contractMetadataContractKeyHash: Rep[Option[String]] =
      column[Option[String]]("contract_metadata_contract_key_hash", O.Default(None))

    val contractMetadataDriverInternal: Rep[Array[Byte]] =
      column[Array[Byte]]("contract_metadata_driver_internal")

    val contractExpiresAt: Rep[Option[Timestamp]] =
      column[Option[Timestamp]]("contract_expires_at", O.Default(None))

    val directoryInstallUser: Rep[Option[String]] =
      column[Option[String]]("directory_install_user", O.Default(None))

    val directoryEntryName: Rep[Option[String]] =
      column[Option[String]]("directory_entry_name", O.Default(None))

    val directoryEntryOwner: Rep[Option[String]] =
      column[Option[String]]("directory_entry_owner", O.Default(None))

    val subscriptionContextContractId: Rep[Option[ContractId]] =
      column[Option[ContractId]]("subscription_context_contract_id", O.Default(None))

    val subscriptionNextPaymentDueAt: Rep[Option[Timestamp]] =
      column[Option[Timestamp]]("subscription_next_payment_due_at", O.Default(None))
  }

  lazy val DirectoryAcsStore = new TableQuery(tag => new DirectoryAcsStore(tag))

}
