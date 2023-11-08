package com.daml.network.directory.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.{Contract, QualifiedName}
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.ByteString

object DirectoryTables extends AcsTables {

  case class DirectoryAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      directoryInstallUser: Option[PartyId],
      directoryEntryName: Option[String],
      directoryEntryOwner: Option[PartyId],
      subscriptionReferenceContractId: Option[ContractId[subsCodegen.SubscriptionRequest]],
      subscriptionNextPaymentDueAt: Option[Timestamp],
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "directory_install_user" -> directoryInstallUser,
      "directory_entry_name" -> directoryEntryName.map(lengthLimited),
      "directory_entry_owner" -> directoryEntryOwner,
      "subscription_reference_contract_id" -> subscriptionReferenceContractId,
      "subscription_next_payment_due_at" -> subscriptionNextPaymentDueAt,
    )
  }

  object DirectoryAcsStoreRowData {
    def fromCreatedEvent(
        createdEvent: CreatedEvent,
        createdEventBlob: ByteString,
    ): Either[String, DirectoryAcsStoreRowData] = {
      // TODO(#8125) Switch to map lookups instead
      QualifiedName(createdEvent.getTemplateId) match {
        case t if t == QualifiedName(directoryCodegen.DirectoryInstall.TEMPLATE_ID) =>
          tryToDecode(directoryCodegen.DirectoryInstall.COMPANION, createdEvent, createdEventBlob) {
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
        case t if t == QualifiedName(directoryCodegen.DirectoryInstallRequest.TEMPLATE_ID) =>
          tryToDecode(
            directoryCodegen.DirectoryInstallRequest.COMPANION,
            createdEvent,
            createdEventBlob,
          ) { contract =>
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
        case t if t == QualifiedName(directoryCodegen.DirectoryEntry.TEMPLATE_ID) =>
          tryToDecode(directoryCodegen.DirectoryEntry.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              DirectoryAcsStoreRowData(
                contract = contract,
                contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
                directoryInstallUser = None,
                directoryEntryName = Some(contract.payload.name),
                directoryEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
                subscriptionReferenceContractId = None,
                subscriptionNextPaymentDueAt = None,
              )
          }
        case t if t == QualifiedName(directoryCodegen.DirectoryEntryContext.TEMPLATE_ID) =>
          tryToDecode(
            directoryCodegen.DirectoryEntryContext.COMPANION,
            createdEvent,
            createdEventBlob,
          ) { contract =>
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
        case t if t == QualifiedName(subsCodegen.SubscriptionIdleState.TEMPLATE_ID) =>
          tryToDecode(subsCodegen.SubscriptionIdleState.COMPANION, createdEvent, createdEventBlob) {
            contract =>
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
        case t if t == QualifiedName(subsCodegen.SubscriptionInitialPayment.TEMPLATE_ID) =>
          tryToDecode(
            subsCodegen.SubscriptionInitialPayment.COMPANION,
            createdEvent,
            createdEventBlob,
          ) { contract =>
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
        case t if t == QualifiedName(subsCodegen.SubscriptionPayment.TEMPLATE_ID) =>
          tryToDecode(subsCodegen.SubscriptionPayment.COMPANION, createdEvent, createdEventBlob) {
            contract =>
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
        case t if t == QualifiedName(subsCodegen.TerminatedSubscription.TEMPLATE_ID) =>
          tryToDecode(
            subsCodegen.TerminatedSubscription.COMPANION,
            createdEvent,
            createdEventBlob,
          ) { contract =>
            DirectoryAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              directoryInstallUser = None,
              directoryEntryName = None,
              directoryEntryOwner = None,
              subscriptionReferenceContractId = None,
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
