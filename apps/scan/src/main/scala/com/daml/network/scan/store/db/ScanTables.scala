package com.daml.network.scan.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn
import com.daml.network.store.db.AcsTables
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.ScanTxLogParser
import com.daml.network.scan.store.ScanTxLogParser.TxLogIndexRecord
import com.digitalasset.canton.config.CantonRequireTypes.String3

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
        scanConfig: ScanAppBackendConfig,
    ): Either[String, ScanAcsStoreRowData] = {
      createdEvent.getTemplateId match {
        case cc.coin.CoinRules.TEMPLATE_ID =>
          tryToDecode(cc.coin.CoinRules.COMPANION, createdEvent) { contract =>
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
        case cn.cns.CnsRules.TEMPLATE_ID =>
          tryToDecode(cn.cns.CnsRules.COMPANION, createdEvent) { contract =>
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
        case ccV1Test.coin.CoinRulesV1Test.TEMPLATE_ID if scanConfig.enableCoinRulesUpgrade =>
          tryToDecode(ccV1Test.coin.CoinRulesV1Test.COMPANION, createdEvent) { contract =>
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
        case cc.round.OpenMiningRound.TEMPLATE_ID =>
          tryToDecode(cc.round.OpenMiningRound.COMPANION, createdEvent) { contract =>
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
        case cc.round.ClosedMiningRound.TEMPLATE_ID =>
          tryToDecode(cc.round.ClosedMiningRound.COMPANION, createdEvent) { contract =>
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
        case cc.round.IssuingMiningRound.TEMPLATE_ID =>
          tryToDecode(cc.round.IssuingMiningRound.COMPANION, createdEvent) { contract =>
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
        case cc.round.SummarizingMiningRound.TEMPLATE_ID =>
          tryToDecode(cc.round.SummarizingMiningRound.COMPANION, createdEvent) { contract =>
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
        case cc.coin.FeaturedAppRight.TEMPLATE_ID =>
          tryToDecode(cc.coin.FeaturedAppRight.COMPANION, createdEvent) { contract =>
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
        case cc.coin.Coin.TEMPLATE_ID =>
          tryToDecode(cc.coin.Coin.COMPANION, createdEvent) { contract =>
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
        case cc.coin.LockedCoin.TEMPLATE_ID =>
          tryToDecode(cc.coin.LockedCoin.COMPANION, createdEvent) { contract =>
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
        case cc.globaldomain.ValidatorTraffic.TEMPLATE_ID =>
          tryToDecode(cc.globaldomain.ValidatorTraffic.COMPANION, createdEvent) { contract =>
            ScanAcsStoreRowData(
              contract = contract,
              contractExpiresAt = None,
              round = None,
              validator = Some(PartyId.tryFromProtoPrimitive(contract.payload.validator)),
              amount = None,
              importCrateReceiver = None,
              featuredAppRightProvider = None,
            )
          }
        case cc.coinimport.ImportCrate.TEMPLATE_ID =>
          tryToDecode(cc.coinimport.ImportCrate.COMPANION, createdEvent) { contract =>
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

    def fromTxLogIndexRecord(record: ScanTxLogParser.TxLogIndexRecord): ScanTxLogRowData = {
      record match {
        case err @ ScanTxLogParser.TxLogIndexRecord.ErrorIndexRecord(offset, eventId, domainId) =>
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
        case omr @ ScanTxLogParser.TxLogIndexRecord.OpenMiningRoundIndexRecord(
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
        case cmr @ ScanTxLogParser.TxLogIndexRecord.ClosedMiningRoundIndexRecord(
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
        case etp @ ScanTxLogParser.TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord(
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
        case bac @ ScanTxLogParser.TxLogIndexRecord.BalanceChangeIndexRecord(
              offset,
              eventId,
              domainId,
              round,
              changeToInitialAmountAsOfRoundZero,
              changeToHoldingFeesRate,
            ) =>
          ScanTxLogRowData(
            eventId = eventId,
            indexRecordType = bac.companion.dbType,
            offset = offset,
            domainId = domainId,
            acsContractId = None,
            round = Some(round),
            rewardAmount = None,
            rewardedParty = None,
            balanceChangeToInitialAmountAsOfRoundZero = Some(changeToInitialAmountAsOfRoundZero),
            balanceChangeChangeToHoldingFeesRate = Some(changeToHoldingFeesRate),
            extraTrafficValidator = None,
            extraTrafficPurchaseTrafficPurchase = None,
            extraTrafficPurchaseCcSpent = None,
          )
        case rar @ ScanTxLogParser.TxLogIndexRecord.RecentActivityIndexRecord(
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

}
