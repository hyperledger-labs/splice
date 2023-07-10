package com.daml.network.unit.scan

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cc.api.v1 as ccApiCodegen
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  coinimport as coinimportCodegen,
  fees as feesCodegen,
  round as roundCodegen,
  schedule as scheduleCodegen,
}
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.protocol.LfContractId
import com.daml.network.history.{CoinExpire, LockedCoinExpireCoin, Transfer}
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.StoreTest
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.{CNNodeUtil, Contract}
import com.digitalasset.canton.{DomainAlias, HasExecutionContext}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf

import java.util.Optional
import java.time.Instant
import scala.concurrent.Future

abstract class ScanStoreTest extends StoreTest with HasExecutionContext {

  private val holdingFee = 1.0

  protected def mkStore(endUserParty: PartyId): Future[ScanStore]

  "CC Scan" should {

    "return correct total coin balance for the round where the transfer happened and for the rounds before and after" in {
      val coinAmount = 100.0
      for {
        store <- mkStore(user1)
        coinRulesContract = coinRules()
        _ <- dummyDomain.exercise(
          coinRulesContract,
          interfaceId = Some(ccApiCodegen.coin.CoinRules.TEMPLATE_ID),
          Transfer.choice.name,
          mkCoinRulesTransfer(user1, coinAmount),
          mkTransferResult(2, coinAmount, holdingFee),
          "011",
        )(
          store.multiDomainAcsStore
        )
      } yield {
        eventually() {
          store.getTotalCoinBalance(1).futureValue shouldBe (0.0)
          // 100.0 is the initial amount as of round 0, so at the end of round 2 the holding fee was applied three times
          store.getTotalCoinBalance(2).futureValue shouldBe (coinAmount - 3 * holdingFee)
          store.getTotalCoinBalance(3).futureValue shouldBe (coinAmount - 4 * holdingFee)
        }
      }
    }

    "return correct total coin balance for the round where the coin expired and for the rounds before and after" in {
      val coinRound1 = 100.0
      val changeToInitialAmountAsOfRoundZero = -50.0
      for {
        store <- mkStore(user1)
        _ <- dummyDomain.ingest(mintTransaction(user1, coinRound1, 1, holdingFee))(
          store.multiDomainAcsStore
        )
        coinContract = coin(user1, coinRound1, 1, holdingFee)
        _ <- dummyDomain.exercise(
          coinContract,
          interfaceId = Some(ccApiCodegen.coin.Coin.TEMPLATE_ID),
          CoinExpire.choice.name,
          mkCoinExpire(),
          mkCoinExpireSummary(
            user1,
            2,
            changeToInitialAmountAsOfRoundZero,
            holdingFee,
          ),
          "011",
        )(
          store.multiDomainAcsStore
        )
      } yield {
        eventually() {
          store.getTotalCoinBalance(1).futureValue shouldBe (coinRound1 - 1 * holdingFee)
          store
            .getTotalCoinBalance(2)
            .futureValue shouldBe (coinRound1 - 2 * holdingFee + changeToInitialAmountAsOfRoundZero - 3 * holdingFee)
          store
            .getTotalCoinBalance(3)
            .futureValue shouldBe (coinRound1 - 3 * holdingFee + changeToInitialAmountAsOfRoundZero - 4 * holdingFee)
        }
      }
    }

    "return correct total coin balance for the round where the locked coin expired and for the rounds before and after" in {
      val coinRound1 = 100.0
      val changeToInitialAmountAsOfRoundZero = -50.0
      for {
        store <- mkStore(user1)
        _ <- dummyDomain.ingest(mintTransaction(user1, coinRound1, 1, holdingFee))(
          store.multiDomainAcsStore
        )
        coinContract = lockedCoin(user1, coinRound1, 1, holdingFee)
        _ <- dummyDomain.exercise(
          coinContract,
          interfaceId = Some(ccApiCodegen.coin.LockedCoin.TEMPLATE_ID),
          LockedCoinExpireCoin.choice.name,
          mkLockedCoinExpireCoin(),
          mkCoinExpireSummary(
            user1,
            2,
            changeToInitialAmountAsOfRoundZero,
            holdingFee,
          ),
          "011",
        )(
          store.multiDomainAcsStore
        )
      } yield {
        eventually() {
          store.getTotalCoinBalance(1).futureValue shouldBe (coinRound1 - 1 * holdingFee)
          store
            .getTotalCoinBalance(2)
            .futureValue shouldBe (coinRound1 - 2 * holdingFee + changeToInitialAmountAsOfRoundZero - 3 * holdingFee)
          store
            .getTotalCoinBalance(3)
            .futureValue shouldBe (coinRound1 - 3 * holdingFee + changeToInitialAmountAsOfRoundZero - 4 * holdingFee)
        }
      }
    }

    "return correct total coin balance for the round where the mint happened and for the rounds before and after" in {
      val mintAmount = 100.0
      for {
        store <- mkStore(user1)
        _ <- dummyDomain.ingest(mintTransaction(user1, mintAmount, 2, holdingFee))(
          store.multiDomainAcsStore
        )
      } yield {
        eventually() {
          store.getTotalCoinBalance(1).futureValue shouldBe (0.0)
          // The coin is minted at round 2, so at the end of that round it's already incurring 1 x holding fee
          store.getTotalCoinBalance(2).futureValue shouldBe (mintAmount - 1 * holdingFee)
          store.getTotalCoinBalance(3).futureValue shouldBe (mintAmount - 2 * holdingFee)
        }
      }
    }

    "return correct total coin balance for the import crates" in {
      val coinRound1Amount = 77.0
      val coinRound2Amount = 88.0
      val round1 = 1L
      val round2 = 2L

      def importCrateForRound(store: ScanStore, amount: BigDecimal, round: Long) =
        dummyDomain.create(
          mkImportCrateContract(svcParty, user1, mkCoin(user1, amount, round, holdingFee))
        )(
          store.multiDomainAcsStore
        )

      for {
        store <- mkStore(user1)
        _ <- importCrateForRound(store, coinRound1Amount, round1)
        _ <- importCrateForRound(store, coinRound2Amount, round2)
      } yield {
        eventually() {
          store
            .getTotalCoinBalance(round1)
            .futureValue shouldBe (coinRound1Amount - round1 * holdingFee)
          val changeToRound1AsOfRound0 = holdingFee * round1
          val changeToRound2AsOfRound0 = holdingFee * round2
          val holdingFeesEndRound2 = holdingFee * round2 + 1
          store.getTotalCoinBalance(round2).futureValue shouldBe (
            coinRound1Amount + changeToRound1AsOfRound0 - holdingFeesEndRound2 +
              coinRound2Amount + changeToRound2AsOfRound0 - holdingFeesEndRound2
          )
        }
      }
    }
  }

  private val user1 = userParty(1)
  private val enabledChoices = CNNodeUtil.defaultEnabledChoices
  private val schedule: scheduleCodegen.Schedule[Instant, CoinConfig[USD]] =
    new scheduleCodegen.Schedule(null, null)

  private var cIdCounter = 0

  private def nextCid() = {
    cIdCounter += 1
    // Note: contract ids that appear in contract payloads need to pass contract id validation,
    // otherwise JSON serialization will fail when storing contracts in the database.
    LfContractId.assertFromString("00" + f"$cIdCounter%064x").coid
  }

  private def coinRules() = {
    val templateId = coinCodegen.CoinRules.TEMPLATE_ID

    val template = new coinCodegen.CoinRules(
      svcParty.toProtoPrimitive,
      schedule,
      enabledChoices,
      false,
      false,
    )
    Contract(
      identifier = templateId,
      contractId = new coinCodegen.CoinRules.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def coin(owner: PartyId, amount: Double, createdAt: Long, ratePerRound: Double) = {
    val templateId = coinCodegen.Coin.TEMPLATE_ID
    val template = new coinCodegen.Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      new feesCodegen.ExpiringAmount(
        new java.math.BigDecimal(amount),
        new ccApiCodegen.round.Round(createdAt),
        new feesCodegen.RatePerRound(new java.math.BigDecimal(ratePerRound)),
      ),
    )
    Contract(
      identifier = templateId,
      contractId = new coinCodegen.Coin.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def lockedCoin(owner: PartyId, amount: Double, createdAt: Long, ratePerRound: Double) = {
    val templateId = coinCodegen.LockedCoin.TEMPLATE_ID
    val coinTemplate = new coinCodegen.Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      new feesCodegen.ExpiringAmount(
        new java.math.BigDecimal(amount),
        new ccApiCodegen.round.Round(createdAt),
        new feesCodegen.RatePerRound(new java.math.BigDecimal(ratePerRound)),
      ),
    )
    val template = new coinCodegen.LockedCoin(
      coinTemplate,
      new ccApiCodegen.coin.TimeLock(java.util.List.of(), Instant.now()),
    )
    Contract(
      identifier = templateId,
      contractId = new coinCodegen.LockedCoin.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def mkCoin(
      owner: PartyId,
      amount: BigDecimal,
      createdAt: Long,
      ratePerRound: BigDecimal,
  ) = {
    new coinCodegen.Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      new feesCodegen.ExpiringAmount(
        amount.bigDecimal,
        new ccApiCodegen.round.Round(createdAt),
        new feesCodegen.RatePerRound(ratePerRound.bigDecimal),
      ),
    )
  }

  private def mkImportCrateContract(svc: PartyId, receiver: PartyId, coin: coinCodegen.Coin) = {
    val templateId = coinimportCodegen.ImportCrate.TEMPLATE_ID
    val template = mkImportCrate(svc, receiver, coin)
    Contract(
      identifier = templateId,
      contractId = new coinimportCodegen.ImportCrate.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def mkImportCrate(svc: PartyId, receiver: PartyId, coin: coinCodegen.Coin) = {
    new coinimportCodegen.ImportCrate(
      svc.toProtoPrimitive,
      receiver.toProtoPrimitive,
      new coinimportCodegen.importpayload.IP_Coin(
        coin
      ),
    )
  }

  private def mkInputCoin() = {
    new ccApiCodegen.coin.transferinput.InputCoin(
      new ccApiCodegen.coin.Coin.ContractId(nextCid())
    )
  }

  private def mkTransferOutput(receiver: PartyId, amount: Double) =
    new ccApiCodegen.coin.TransferOutput(
      receiver.toProtoPrimitive,
      new java.math.BigDecimal(0.0),
      new java.math.BigDecimal(amount),
      Optional.empty(),
    )

  private def mkTransfer(receiver: PartyId, amount: Double) =
    new ccApiCodegen.coin.Transfer(
      receiver.toProtoPrimitive,
      receiver.toProtoPrimitive,
      java.util.List.of(mkInputCoin()),
      java.util.List.of(mkTransferOutput(receiver, amount)),
    )

  private def mkTransferContext() = new ccApiCodegen.coin.TransferContext(
    new ccApiCodegen.round.OpenMiningRound.ContractId(nextCid()),
    java.util.Map.of(),
    java.util.Map.of(),
    Optional.empty(),
  )

  private def mkCoinRulesTransfer(receiver: PartyId, amount: Double) =
    new ccApiCodegen.coin.CoinRules_Transfer(
      mkTransfer(receiver, amount),
      mkTransferContext(),
    ).toValue

  private def mkTransferSummary(
      changeToInitialAmountAsOfRoundZero: Double,
      changeToHoldingFeesRate: Double,
  ) = new ccApiCodegen.coin.TransferSummary(
    new java.math.BigDecimal(0.0),
    new java.math.BigDecimal(0.0),
    new java.math.BigDecimal(0.0),
    new java.math.BigDecimal(0.0),
    java.util.List.of(new java.math.BigDecimal(0.0)),
    new java.math.BigDecimal(0.0),
    new java.math.BigDecimal(0.0),
    new java.math.BigDecimal(0.0),
    new java.math.BigDecimal(changeToInitialAmountAsOfRoundZero),
    new java.math.BigDecimal(changeToHoldingFeesRate),
  )

  private def mkTransferResult(
      round: Long,
      changeToInitialAmountAsOfRoundZero: Double,
      changeToHoldingFeesRate: Double,
  ) =
    new ccApiCodegen.coin.TransferResult(
      new ccApiCodegen.round.Round(round),
      mkTransferSummary(changeToInitialAmountAsOfRoundZero, changeToHoldingFeesRate),
      java.util.List.of(),
      Optional.empty(),
    ).toValue

  /** A CoinRules_Mint exercise event with one child Coin create event */
  private def mintTransaction(
      receiver: PartyId,
      amount: Double,
      round: Long,
      ratePerRound: Double,
      coinPrice: Double = 1.0,
  )(
      offset: String
  ) = {
    val coinContract = coin(receiver, amount, round, ratePerRound)

    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val coinRulesCid = nextCid()
    val openMiningRoundCid = nextCid()

    mkExerciseTx(
      offset,
      exercisedEvent(
        coinRulesCid,
        coinCodegen.CoinRules.TEMPLATE_ID,
        None,
        coinCodegen.CoinRules.CHOICE_CoinRules_Mint.name,
        consuming = false,
        new coinCodegen.CoinRules_Mint(
          receiver.toProtoPrimitive,
          coinContract.payload.amount.initialAmount,
          new roundCodegen.OpenMiningRound.ContractId(openMiningRoundCid),
        ).toValue,
        new ccApiCodegen.coin.CoinCreateSummary[coinCodegen.Coin.ContractId](
          coinContract.contractId,
          new java.math.BigDecimal(coinPrice),
        )
          .toValue(_.toValue),
      ),
      Seq(toCreatedEvent(coinContract)),
    )
  }

  private def mkCoinExpire() =
    new coinCodegen.Coin_Expire(
      new roundCodegen.OpenMiningRound.ContractId(nextCid()),
      new ccApiCodegen.coin.CoinRules.ContractId(nextCid()),
    ).toValue

  private def mkLockedCoinExpireCoin() =
    new coinCodegen.LockedCoin_ExpireCoin(
      new roundCodegen.OpenMiningRound.ContractId(nextCid()),
      new ccApiCodegen.coin.CoinRules.ContractId(nextCid()),
    ).toValue

  private def mkCoinExpireSummary(
      owner: PartyId,
      round: Long,
      changeToInitialAmountAsOfRoundZero: Double,
      changeToHoldingFeesRate: Double,
  ) =
    new ccApiCodegen.coin.CoinExpireSummary(
      owner.toProtoPrimitive,
      new ccApiCodegen.round.Round(round),
      new java.math.BigDecimal(changeToInitialAmountAsOfRoundZero),
      new java.math.BigDecimal(changeToHoldingFeesRate),
    ).toValue

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val domainAlias = DomainAlias.tryCreate(domain)

}

class InMemoryScanStoreTest extends ScanStoreTest {
  override protected def mkStore(endUserParty: PartyId): Future[ScanStore] = {
    val store = new InMemoryScanStore(
      endUserParty,
      ScanAppBackendConfig(svUser = "SvUser", participantClient = null, domains = null),
      loggerFactory = loggerFactory,
      // The ledger connection is only used for reading full TxLog entries
      TransactionTreeSource.LedgerConnection(svcParty, connection = null),
      retryProvider = RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(domainAlias -> dummyDomain)
      )
    } yield store
  }

}
