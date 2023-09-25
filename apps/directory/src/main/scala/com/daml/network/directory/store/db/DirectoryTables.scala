package com.daml.network.directory.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionContext
import com.daml.network.store.db.AcsTables
import com.daml.network.store.db.AcsTables.AcsStoreTemplate
import com.daml.network.util.Contract
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
      // Index columns
      directoryInstallUser: Option[PartyId] = None,
      directoryEntryName: Option[String] = None,
      directoryEntryOwner: Option[PartyId] = None,
      subscriptionContextContractId: Option[ContractId[SubscriptionContext]] = None,
      subscriptionNextPaymentDueAt: Option[Timestamp] = None,
  )

  case class DirectoryAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      directoryInstallUser: Option[PartyId],
      directoryEntryName: Option[String],
      directoryEntryOwner: Option[PartyId],
      subscriptionContextContractId: Option[ContractId[SubscriptionContext]],
      subscriptionNextPaymentDueAt: Option[Timestamp],
  )

  object DirectoryAcsStoreRowData {
    def fromCreatedEvent(createdEvent: CreatedEvent): Either[String, DirectoryAcsStoreRowData] = {
      createdEvent.getTemplateId match {
        case directoryCodegen.DirectoryInstall.TEMPLATE_ID =>
          tryToDecode(directoryCodegen.DirectoryInstall.COMPANION, createdEvent) { contract =>
            DirectoryAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              directoryInstallUser = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
              directoryEntryName = None,
              directoryEntryOwner = None,
              subscriptionContextContractId = None,
              subscriptionNextPaymentDueAt = None,
            )
          }
        case directoryCodegen.DirectoryInstallRequest.TEMPLATE_ID =>
          tryToDecode(directoryCodegen.DirectoryInstallRequest.COMPANION, createdEvent) {
            contract =>
              DirectoryAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                directoryInstallUser = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
                directoryEntryName = None,
                directoryEntryOwner = None,
                subscriptionContextContractId = None,
                subscriptionNextPaymentDueAt = None,
              )
          }
        case directoryCodegen.DirectoryEntry.TEMPLATE_ID =>
          tryToDecode(directoryCodegen.DirectoryEntry.COMPANION, createdEvent) { contract =>
            DirectoryAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              directoryInstallUser = None,
              directoryEntryName = Some(contract.payload.name),
              directoryEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
              subscriptionContextContractId = None,
              subscriptionNextPaymentDueAt = None,
            )
          }
        case directoryCodegen.DirectoryEntryContext.TEMPLATE_ID =>
          tryToDecode(directoryCodegen.DirectoryEntryContext.COMPANION, createdEvent) { contract =>
            DirectoryAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              directoryInstallUser = None,
              directoryEntryName = Some(contract.payload.name),
              directoryEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
              subscriptionContextContractId = None,
              subscriptionNextPaymentDueAt = None,
            )
          }
        case subsCodegen.SubscriptionIdleState.TEMPLATE_ID =>
          tryToDecode(subsCodegen.SubscriptionIdleState.COMPANION, createdEvent) { contract =>
            DirectoryAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              directoryInstallUser = None,
              directoryEntryName = None,
              directoryEntryOwner = None,
              subscriptionContextContractId = Some(contract.payload.subscriptionData.context),
              subscriptionNextPaymentDueAt =
                Some(Timestamp.assertFromInstant(contract.payload.nextPaymentDueAt)),
            )
          }
        case subsCodegen.SubscriptionInitialPayment.TEMPLATE_ID =>
          tryToDecode(subsCodegen.SubscriptionInitialPayment.COMPANION, createdEvent) { contract =>
            DirectoryAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              directoryInstallUser = None,
              directoryEntryName = None,
              directoryEntryOwner = None,
              subscriptionContextContractId = Some(contract.payload.subscriptionData.context),
              subscriptionNextPaymentDueAt = None,
            )
          }
        case subsCodegen.SubscriptionPayment.TEMPLATE_ID =>
          tryToDecode(subsCodegen.SubscriptionPayment.COMPANION, createdEvent) { contract =>
            DirectoryAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              directoryInstallUser = None,
              directoryEntryName = None,
              directoryEntryOwner = None,
              subscriptionContextContractId = Some(contract.payload.subscriptionData.context),
              subscriptionNextPaymentDueAt = None,
            )
          }
        case t =>
          Left(s"Template $t cannot be decoded as an entry for the directory store.")
      }
    }
  }

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
