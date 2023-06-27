package com.daml.network.scan.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.store.db.AcsTables
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.java.cc
import com.daml.network.scan.config.ScanAppBackendConfig

object ScanTables extends AcsTables {

  case class ScanAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp],
      round: Option[Long],
      validator: Option[PartyId],
      amount: Option[BigDecimal],
      importCrateReceiverName: Option[String],
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = None,
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
              importCrateReceiverName = Some(contract.payload.receiverName),
              featuredAppRightProvider = None,
            )
          }
        case t =>
          Left(s"Template $t cannot be decoded as an entry for the scan store.")
      }
    }

  }

}
