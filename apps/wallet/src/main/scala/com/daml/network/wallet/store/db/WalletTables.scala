package com.daml.network.wallet.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.store.db.AcsTables
import com.daml.network.util.{Contract, QualifiedName}
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.topology.DomainId
import com.google.protobuf.ByteString

object WalletTables extends AcsTables {

  case class UserWalletAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
  )

  object UserWalletAcsStoreRowData {
    def fromCreatedEvent(
        createdEvent: CreatedEvent,
        createdEventBlob: ByteString,
    ): Either[String, UserWalletAcsStoreRowData] = {
      def noIndex(contract: Contract[?, ?]) =
        UserWalletAcsStoreRowData(
          contract = contract,
          contractExpiresAt = None,
        )

      // TODO(#8125) Switch to map lookups instead
      QualifiedName(createdEvent.getTemplateId) match {
        case t if t == QualifiedName(installCodegen.WalletAppInstall.TEMPLATE_ID) =>
          tryToDecode(installCodegen.WalletAppInstall.COMPANION, createdEvent, createdEventBlob)(
            noIndex
          )
        case t if t == QualifiedName(coinCodegen.Coin.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.Coin.COMPANION, createdEvent, createdEventBlob)(noIndex)
        case t if t == QualifiedName(coinCodegen.LockedCoin.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.LockedCoin.COMPANION, createdEvent, createdEventBlob)(noIndex)
        case t if t == QualifiedName(coinCodegen.AppRewardCoupon.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.AppRewardCoupon.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              UserWalletAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
              )
          )
        case t if t == QualifiedName(coinCodegen.ValidatorRewardCoupon.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.ValidatorRewardCoupon.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              UserWalletAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
              )
          )
        case t if t == QualifiedName(coinCodegen.ValidatorRight.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.ValidatorRight.COMPANION, createdEvent, createdEventBlob)(noIndex)
        case t if t == QualifiedName(transferOffersCodegen.TransferOffer.TEMPLATE_ID) =>
          tryToDecode(
            transferOffersCodegen.TransferOffer.COMPANION,
            createdEvent,
            createdEventBlob,
          )(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
            )
          )
        case t if t == QualifiedName(transferOffersCodegen.AcceptedTransferOffer.TEMPLATE_ID) =>
          tryToDecode(
            transferOffersCodegen.AcceptedTransferOffer.COMPANION,
            createdEvent,
            createdEventBlob,
          )(contract =>
            UserWalletAcsStoreRowData(
              contract = contract,
              contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
            )
          )
        case t if t == QualifiedName(walletCodegen.AppPaymentRequest.TEMPLATE_ID) =>
          tryToDecode(walletCodegen.AppPaymentRequest.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              UserWalletAcsStoreRowData(
                contract = contract,
                contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
              )
          )
        case t if t == QualifiedName(walletCodegen.AcceptedAppPayment.TEMPLATE_ID) =>
          tryToDecode(walletCodegen.AcceptedAppPayment.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              UserWalletAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
              )
          )
        case t if t == QualifiedName(subsCodegen.Subscription.TEMPLATE_ID) =>
          tryToDecode(subsCodegen.Subscription.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              UserWalletAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
              )
          )
        case t if t == QualifiedName(subsCodegen.SubscriptionRequest.TEMPLATE_ID) =>
          tryToDecode(subsCodegen.SubscriptionRequest.COMPANION, createdEvent, createdEventBlob)(
            noIndex
          )
        case t if t == QualifiedName(subsCodegen.SubscriptionIdleState.TEMPLATE_ID) =>
          tryToDecode(subsCodegen.SubscriptionIdleState.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              UserWalletAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
              )
          )
        case t if t == QualifiedName(subsCodegen.SubscriptionInitialPayment.TEMPLATE_ID) =>
          tryToDecode(
            subsCodegen.SubscriptionInitialPayment.COMPANION,
            createdEvent,
            createdEventBlob,
          )(noIndex)
        case t if t == QualifiedName(subsCodegen.SubscriptionPayment.TEMPLATE_ID) =>
          tryToDecode(subsCodegen.SubscriptionPayment.COMPANION, createdEvent, createdEventBlob)(
            contract =>
              UserWalletAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
              )
          )
        case t if t == QualifiedName(coinCodegen.FeaturedAppRight.TEMPLATE_ID) =>
          tryToDecode(coinCodegen.FeaturedAppRight.COMPANION, createdEvent, createdEventBlob)(
            noIndex
          )
        case t if t == QualifiedName(dirCodegen.DirectoryInstall.TEMPLATE_ID) =>
          tryToDecode(dirCodegen.DirectoryInstall.COMPANION, createdEvent, createdEventBlob)(
            noIndex
          )
        case t if t == QualifiedName(dirCodegen.DirectoryEntry.TEMPLATE_ID) =>
          tryToDecode(dirCodegen.DirectoryEntry.COMPANION, createdEvent, createdEventBlob)(noIndex)
        case t if t == QualifiedName(dirCodegen.DirectoryEntryContext.TEMPLATE_ID) =>
          tryToDecode(dirCodegen.DirectoryEntryContext.COMPANION, createdEvent, createdEventBlob)(
            noIndex
          )
        case t =>
          Left(s"Template $t cannot be decoded as an entry for the user wallet store.")
      }
    }
  }

  // Note: currently the index record is empty, but this is likely to change once we want to support more advanced
  // filtering/sorting of the transaction history.
  case class UserWalletTxLogStoreRowData(
      eventId: String,
      optOffset: Option[String],
      domainId: DomainId,
      acsContractId: Option[ContractId[?]],
      txLogId: String3,
      transferOfferTrackingId: Option[String],
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
          indexRecord match {
            case _: UserWalletTxLogParser.TransactionHistoryTxLogIndexRecord =>
              None
            case to: UserWalletTxLogParser.TransferOfferStatusTxLogIndexRecord =>
              Some(to.trackingId)
          },
        )
      )
  }

  val acsTableName: String = "user_wallet_acs_store"
  val txLogTableName: String = "user_wallet_txlog_store"
}
