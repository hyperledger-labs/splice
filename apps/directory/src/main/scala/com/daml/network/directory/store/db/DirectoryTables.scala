package com.daml.network.directory.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.store.db.AcsTables
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId

object DirectoryTables extends AcsTables {

  case class DirectoryAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      directoryInstallUser: Option[PartyId],
      directoryEntryName: Option[String],
      directoryEntryOwner: Option[PartyId],
      subscriptionReferenceContractId: Option[ContractId[subsCodegen.SubscriptionRequest]],
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
              subscriptionReferenceContractId = None,
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
                subscriptionReferenceContractId = None,
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
              subscriptionReferenceContractId = None,
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
              subscriptionReferenceContractId = Some(contract.payload.reference),
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
              subscriptionReferenceContractId = Some(contract.payload.reference),
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
              subscriptionReferenceContractId = Some(contract.payload.reference),
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
              subscriptionReferenceContractId = Some(contract.payload.reference),
              subscriptionNextPaymentDueAt = None,
            )
          }
        case t =>
          Left(s"Template $t cannot be decoded as an entry for the directory store.")
      }
    }
  }

  val acsTableName = "directory_acs_store"
}
