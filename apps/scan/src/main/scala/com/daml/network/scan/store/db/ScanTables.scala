package com.daml.network.scan.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn
import com.daml.network.store.db.AcsTables
import com.daml.network.util.{Contract, QualifiedName}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.daml.network.scan.store.TxLogIndexRecord
import com.digitalasset.canton.config.CantonRequireTypes.String3

import com.google.protobuf.ByteString

object ScanTables extends AcsTables {

  case class ScanAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      round: Option[Long],
      validator: Option[PartyId],
      amount: Option[BigDecimal],
      importCrateReceiver: Option[String],
      featuredAppRightProvider: Option[PartyId],
  )

  object ScanAcsStoreRowData {

    def fromCreatedEvent(
        createdEvent: CreatedEvent,
        createdEventBlob: ByteString,
    ): Either[String, ScanAcsStoreRowData] = {
      // TODO(#8125) Switch to map lookups instead
      QualifiedName(createdEvent.getTemplateId) match {
        case t if t == QualifiedName(cc.coin.CoinRules.TEMPLATE_ID) =>
          tryToDecode(cc.coin.CoinRules.COMPANION, createdEvent, createdEventBlob) { contract =>
            ScanAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              round = None,
              validator = None,
              amount = None,
              importCrateReceiver = None,
              featuredAppRightProvider = None,
            )
          }
        case t if t == QualifiedName(cn.cns.CnsRules.TEMPLATE_ID) =>
          tryToDecode(cn.cns.CnsRules.COMPANION, createdEvent, createdEventBlob) { contract =>
            ScanAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              round = None,
              validator = None,
              amount = None,
              importCrateReceiver = None,
              featuredAppRightProvider = None,
            )
          }
        case t if t == QualifiedName(cn.svcrules.SvcRules.TEMPLATE_ID) =>
          tryToDecode(cn.svcrules.SvcRules.COMPANION, createdEvent, createdEventBlob) { contract =>
            ScanAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              round = None,
              validator = None,
              amount = None,
              importCrateReceiver = None,
              featuredAppRightProvider = None,
            )
          }
        case t if t == QualifiedName(cc.round.OpenMiningRound.TEMPLATE_ID) =>
          tryToDecode(cc.round.OpenMiningRound.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              ScanAcsStoreRowData(
                contract = contract,
                contractExpiresAt =
                  Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
                round = Some(contract.payload.round.number),
                validator = None,
                amount = None,
                importCrateReceiver = None,
                featuredAppRightProvider = None,
              )
          }
        case t if t == QualifiedName(cc.round.ClosedMiningRound.TEMPLATE_ID) =>
          tryToDecode(cc.round.ClosedMiningRound.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              ScanAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                round = Some(contract.payload.round.number),
                validator = None,
                amount = None,
                importCrateReceiver = None,
                featuredAppRightProvider = None,
              )
          }
        case t if t == QualifiedName(cc.round.IssuingMiningRound.TEMPLATE_ID) =>
          tryToDecode(cc.round.IssuingMiningRound.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              ScanAcsStoreRowData(
                contract = contract,
                contractExpiresAt =
                  Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
                round = Some(contract.payload.round.number),
                validator = None,
                amount = None,
                importCrateReceiver = None,
                featuredAppRightProvider = None,
              )
          }
        case t if t == QualifiedName(cc.round.SummarizingMiningRound.TEMPLATE_ID) =>
          tryToDecode(cc.round.SummarizingMiningRound.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              ScanAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                round = Some(contract.payload.round.number),
                validator = None,
                amount = None,
                importCrateReceiver = None,
                featuredAppRightProvider = None,
              )
          }
        case t if t == QualifiedName(cc.coin.FeaturedAppRight.TEMPLATE_ID) =>
          tryToDecode(cc.coin.FeaturedAppRight.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              ScanAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                round = None,
                validator = None,
                amount = None,
                importCrateReceiver = None,
                featuredAppRightProvider =
                  Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
              )
          }
        case t if t == QualifiedName(cc.coin.Coin.TEMPLATE_ID) =>
          tryToDecode(cc.coin.Coin.COMPANION, createdEvent, createdEventBlob) { contract =>
            ScanAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              round = None,
              validator = None,
              amount = Some(contract.payload.amount.initialAmount),
              importCrateReceiver = None,
              featuredAppRightProvider = None,
            )
          }
        case t if t == QualifiedName(cc.coin.LockedCoin.TEMPLATE_ID) =>
          tryToDecode(cc.coin.LockedCoin.COMPANION, createdEvent, createdEventBlob) { contract =>
            ScanAcsStoreRowData(
              contract = contract,
              contractExpiresAt =
                Some(Timestamp.assertFromInstant(contract.payload.lock.expiresAt)),
              round = None,
              validator = None,
              amount = Some(contract.payload.coin.amount.initialAmount),
              importCrateReceiver = None,
              featuredAppRightProvider = None,
            )
          }
        case t if t == QualifiedName(cc.coinimport.ImportCrate.TEMPLATE_ID) =>
          tryToDecode(cc.coinimport.ImportCrate.COMPANION, createdEvent, createdEventBlob) {
            contract =>
              ScanAcsStoreRowData(
                contract = contract,
                contractExpiresAt = None,
                round = None,
                validator = None,
                amount = None,
                importCrateReceiver = Some(contract.payload.receiver),
                featuredAppRightProvider = None,
              )
          }
        case t =>
          Left(s"Template $t cannot be decoded as an entry for the scan store.")
      }
    }

  }

  case class ScanTxLogRowData(
      eventId: String,
      offset: Option[String],
      domainId: DomainId,
      acsContractId: Option[ContractId[?]],
      indexRecordType: String3,
      round: Option[Long],
      rewardAmount: Option[BigDecimal],
      rewardedParty: Option[PartyId],
      balanceChangeToInitialAmountAsOfRoundZero: Option[BigDecimal],
      balanceChangeChangeToHoldingFeesRate: Option[BigDecimal],
      extraTrafficValidator: Option[PartyId],
      extraTrafficPurchaseTrafficPurchase: Option[Long],
      extraTrafficPurchaseCcSpent: Option[BigDecimal],
  )

  object ScanTxLogRowData {

    def fromTxLogIndexRecord(record: TxLogIndexRecord): ScanTxLogRowData = {
      record match {
        case err @ TxLogIndexRecord.ErrorIndexRecord(offset, eventId, domainId) =>
          ScanTxLogRowData(
            eventId = eventId,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            indexRecordType = err.companion.dbType,
            round = None,
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case omr @ TxLogIndexRecord.OpenMiningRoundIndexRecord(
              offset,
              eventId,
              domainId,
              round,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = omr.companion.dbType,
            domainId = domainId,
            acsContractId = None,
            offset = Some(offset),
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case cmr @ TxLogIndexRecord.ClosedMiningRoundIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              _,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = cmr.companion.dbType,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case are @ TxLogIndexRecord.AppRewardIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              party,
              amount,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = are.companion.dbType,
            domainId = domainId,
            offset = Some(offset),
            acsContractId = None,
            round = Some(round),
            rewardAmount = Some(amount),
            rewardedParty = Some(party),
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case vre @ TxLogIndexRecord.ValidatorRewardIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              party,
              amount,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = vre.companion.dbType,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            round = Some(round),
            rewardAmount = Some(amount),
            rewardedParty = Some(party),
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case etp @ TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              validator,
              trafficPurchased,
              ccSpent,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = etp.companion.dbType,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = Some(validator),
            extraTrafficPurchaseTrafficPurchase = Some(trafficPurchased),
            extraTrafficPurchaseCcSpent = Some(ccSpent),
          )
        case bac @ TxLogIndexRecord.BalanceChangeIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate,
              acsContractId,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = bac.companion.dbType,
            offset = offset,
            domainId = domainId,
            acsContractId = acsContractId,
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = Some(changeToInitialAmountAsOfRoundZero),
            balanceChangeChangeToHoldingFeesRate = Some(changeToHoldingFeesRate),
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case rar @ TxLogIndexRecord.TransactionIndexRecord(
              offset,
              eventId,
              domainId,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = rar.companion.dbType,
            offset = Some(offset),
            domainId = domainId,
            acsContractId = None,
            round = None,
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = None,
            balanceChangeChangeToHoldingFeesRate = None,
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
      }
    }

  }

  val acsTableName = "scan_acs_store"
  val txLogTableName = "scan_txlog_store"
}
