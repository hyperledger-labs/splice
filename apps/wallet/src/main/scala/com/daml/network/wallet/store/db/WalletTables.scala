package com.daml.network.wallet.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.store.MultiDomainAcsStore.ContractFilter
import com.daml.network.store.db.AcsTables
import com.daml.network.store.db.AcsTables.{AcsStoreTemplate, TxLogStoreTemplate}
import com.daml.network.util.Contract
import com.daml.network.util.Contract.Companion
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.DomainId
import io.circe.Json
import shapeless.HNil

object WalletTables extends AcsTables {
  import profile.api.*

  lazy val schema: profile.SchemaDescription = acsBaseSchema ++ UserWalletAcsStore.schema

  case class UserWalletAcsStoreRow(
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

  case class UserWalletAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
  )

  object UserWalletAcsStoreRowData {
    def fromCreatedEvent(
        createdEvent: CreatedEvent,
        contractFilter: ContractFilter,
    ): Either[String, UserWalletAcsStoreRowData] = {
      def noIndex(contract: Contract[?, ?]) =
        UserWalletAcsStoreRowData(
          contract = contract,
          contractExpiresAt = None,
        )

      createdEvent.getTemplateId match {
        case installCodegen.WalletAppInstall.TEMPLATE_ID =>
          tryToDecode(installCodegen.WalletAppInstall.COMPANION, createdEvent)(noIndex)
        case coinCodegen.Coin.TEMPLATE_ID =>
          tryToDecode(coinCodegen.Coin.COMPANION, createdEvent)(noIndex)
        case coinCodegen.LockedCoin.TEMPLATE_ID =>
          tryToDecode(coinCodegen.LockedCoin.COMPANION, createdEvent)(noIndex)
        case coinCodegen.AppRewardCoupon.TEMPLATE_ID =>
          tryToDecode(coinCodegen.AppRewardCoupon.COMPANION, createdEvent)(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
            )
          )
        case coinCodegen.ValidatorRewardCoupon.TEMPLATE_ID =>
          tryToDecode(coinCodegen.ValidatorRewardCoupon.COMPANION, createdEvent)(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
            )
          )
        case coinCodegen.ValidatorRight.TEMPLATE_ID =>
          tryToDecode(coinCodegen.ValidatorRight.COMPANION, createdEvent)(noIndex)
        case transferOffersCodegen.TransferOffer.TEMPLATE_ID =>
          tryToDecode(transferOffersCodegen.TransferOffer.COMPANION, createdEvent)(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
            )
          )
        case transferOffersCodegen.AcceptedTransferOffer.TEMPLATE_ID =>
          tryToDecode(transferOffersCodegen.AcceptedTransferOffer.COMPANION, createdEvent)(
            contract =>
              UserWalletAcsStoreRowData(
                contract = contract,
                contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
              )
          )
        case walletCodegen.AppPaymentRequest.TEMPLATE_ID =>
          tryToDecode(walletCodegen.AppPaymentRequest.COMPANION, createdEvent)(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
            )
          )
        case walletCodegen.AcceptedAppPayment.TEMPLATE_ID =>
          tryToDecode(walletCodegen.AcceptedAppPayment.COMPANION, createdEvent)(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
            )
          )
        case subsCodegen.Subscription.TEMPLATE_ID =>
          tryToDecode(subsCodegen.Subscription.COMPANION, createdEvent)(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
            )
          )
        case subsCodegen.SubscriptionRequest.TEMPLATE_ID =>
          tryToDecode(subsCodegen.SubscriptionRequest.COMPANION, createdEvent)(noIndex)
        case subsCodegen.SubscriptionIdleState.TEMPLATE_ID =>
          tryToDecode(subsCodegen.SubscriptionIdleState.COMPANION, createdEvent)(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
            )
          )
        case subsCodegen.SubscriptionInitialPayment.TEMPLATE_ID =>
          tryToDecode(subsCodegen.SubscriptionInitialPayment.COMPANION, createdEvent)(noIndex)
        case subsCodegen.SubscriptionPayment.TEMPLATE_ID =>
          tryToDecode(subsCodegen.SubscriptionPayment.COMPANION, createdEvent)(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
            )
          )
        case coinCodegen.FeaturedAppRight.TEMPLATE_ID =>
          tryToDecode(coinCodegen.FeaturedAppRight.COMPANION, createdEvent)(noIndex)
        case t =>
          // TODO (#2676) Remove the hacky interface decoding machinery once we have proper interface support for multi-domain.
          tryToDecode(
            subsCodegen.SubscriptionContext.INTERFACE,
            createdEvent,
            contractFilter,
          )(noIndex)
            .orElse(
              tryToDecode(
                walletCodegen.DeliveryOffer.INTERFACE,
                createdEvent,
                contractFilter,
              )(noIndex)
            )
            .orElse(
              Left(s"Template $t cannot be decoded as an entry for the user wallet store.")
            )
      }
    }

    private def tryToDecode[TCid <: ContractId[?], T <: DamlRecord[?]](
        companion: Companion.Template[TCid, T],
        createdEvent: CreatedEvent,
    )(
        toData: Contract[TCid, T] => UserWalletAcsStoreRowData
    ): Either[String, UserWalletAcsStoreRowData] = {
      Contract
        .fromCreatedEvent(companion)(createdEvent)
        .map(toData)
        .toRight(
          s"Failed to decode ${companion.TEMPLATE_ID} from CreatedEvent of contract id ${createdEvent.getContractId}."
        )
    }

    def tryToDecode[I, TCid <: ContractId[I], View <: DamlRecord[?]](
        companion: Companion.Interface[TCid, I, View],
        createdEvent: CreatedEvent,
        contractFilter: ContractFilter,
    )(
        toData: Contract[TCid, View] => UserWalletAcsStoreRowData
    ): Either[String, UserWalletAcsStoreRowData] = {
      contractFilter
        .decodeInterface(companion)(createdEvent)
        .map(toData)
        .toRight(
          s"Failed to decode ${companion.TEMPLATE_ID} from CreatedEvent of contract id ${createdEvent.getContractId}."
        )
    }
  }

  class UserWalletAcsStore(_tableTag: Tag)
      extends AcsStoreTemplate[UserWalletAcsStoreRow](_tableTag, "user_wallet_acs_store") {
    def * =
      (templateColumns ::: HNil).tupled
        .<>(UserWalletAcsStoreRow.tupled, UserWalletAcsStoreRow.unapply)
  }

  lazy val UserWalletAcsStore = new TableQuery(tag => new UserWalletAcsStore(tag))

  case class UserWalletTxLogStoreRow(
      storeId: Int,
      entryNumber: Long,
      eventId: String,
      offset: Option[String],
      domainId: DomainId,
      txLogId: String,
  )

  // Note: currently the index record is empty, but this is likely to change once we want to support more advanced
  // filtering/sorting of the transaction history.
  case class UserWalletTxLogStoreRowData(
      eventId: String,
      optOffset: Option[String],
      domainId: DomainId,
      acsContractId: Option[ContractId[?]],
      txLogId: String3,
  )

  object UserWalletTxLogStoreRowData {
    def fromIndexRecord(
        indexRecord: UserWalletTxLogParser.WalletTxLogIndexRecord
    ): Either[String, UserWalletTxLogStoreRowData] =
      Right(
        UserWalletTxLogStoreRowData(
          indexRecord.eventId,
          indexRecord.optOffset,
          indexRecord.domainId,
          indexRecord.acsContractId,
          indexRecord.txLogId,
        )
      )
  }

  class UserWalletTxLogStore(_tableTag: Tag)
      extends TxLogStoreTemplate[UserWalletTxLogStoreRow](_tableTag, "user_wallet_txlog_store") {
    val txLogId: Rep[String] = column[String]("tx_log_id")
    def * =
      (templateColumns ::: txLogId :: HNil).tupled
        .<>(UserWalletTxLogStoreRow.tupled, UserWalletTxLogStoreRow.unapply)
  }

  lazy val UserWalletTxLogStore = new TableQuery(tag => new UserWalletTxLogStore(tag))
}
