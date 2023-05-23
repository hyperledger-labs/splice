package com.daml.network.directory.store.tables

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionContext
import com.daml.network.store.tables.AcsTables
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.PartyId
import io.circe.Json
import shapeless.HNil

object DirectoryTables extends AcsTables {
  import profile.api.*

  lazy val schema: profile.SchemaDescription = acsBaseSchema ++ DirectoryAcsStore.schema

  case class DirectoryAcsStoreRow(
      storeId: Int,
      eventNumber: Long,
      contractId: ContractId[Any],
      templateId: TemplateId,
      createArguments: Json,
      contractMetadataCreatedAt: Timestamp,
      contractMetadataContractKeyHash: Option[String] = None,
      contractMetadataDriverInternal: Array[Byte],
      contractExpiresAt: Option[Timestamp] = None,
      directoryInstallUser: Option[PartyId] = None,
      directoryEntryName: Option[String] = None,
      directoryEntryOwner: Option[PartyId] = None,
      subscriptionContextContractId: Option[ContractId[SubscriptionContext]] = None,
      subscriptionNextPaymentDueAt: Option[Timestamp] = None,
  )

  class DirectoryAcsStore(_tableTag: Tag)
      extends AcsStoreTemplate[DirectoryAcsStoreRow](_tableTag, "directory_acs_store") {
    def * = (
      templateColumns :::
        directoryInstallUser ::
        directoryEntryName ::
        directoryEntryOwner ::
        subscriptionContextContractId ::
        subscriptionNextPaymentDueAt :: HNil
    ).tupled.<>(DirectoryAcsStoreRow.tupled, DirectoryAcsStoreRow.unapply)

    val directoryInstallUser: Rep[Option[PartyId]] =
      column[Option[PartyId]]("directory_install_user", O.Default(None))

    val directoryEntryName: Rep[Option[String]] =
      column[Option[String]]("directory_entry_name", O.Default(None))

    val directoryEntryOwner: Rep[Option[PartyId]] =
      column[Option[PartyId]]("directory_entry_owner", O.Default(None))

    val subscriptionContextContractId: Rep[Option[ContractId[SubscriptionContext]]] =
      column[Option[ContractId[SubscriptionContext]]](
        "subscription_context_contract_id",
        O.Default(None),
      )

    val subscriptionNextPaymentDueAt: Rep[Option[Timestamp]] =
      column[Option[Timestamp]]("subscription_next_payment_due_at", O.Default(None))
  }

  lazy val DirectoryAcsStore = new TableQuery(tag => new DirectoryAcsStore(tag))

}
