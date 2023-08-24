package com.daml.network.store.db

import com.daml.ledger.javaapi.data.{ContractMetadata, DamlRecord}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.api.v1 as ccApiCodegen
import com.daml.network.codegen.java.cc.api.v1.coin.PaymentTransferContext
import com.daml.network.codegen.java.cc.coin.{Coin, FeaturedAppRight}
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.coinimport.importpayload.IP_Coin
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  fees as feesCodegen,
  round as roundCodegen,
  schedule as scheduleCodegen,
}
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types as daTypes
import com.daml.network.config.{CNDbConfig, DomainConfig}
import com.daml.network.environment.RetryProvider
import com.daml.network.history.{
  CoinExpire,
  CoinRules_BuyExtraTraffic,
  LockedCoinExpireCoin,
  Transfer,
}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.config.{ScanAppBackendConfig, ScanDomainConfig}
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.store.ScanTxLogParser.{TxLogIndexRecord, TxLogEntry}
import com.daml.network.scan.store.db.DbScanStore
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{StoreErrors, StoreTest, TxLogStore}
import com.daml.network.util.{CNNodeUtil, Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf
import java.time.{Duration, Instant}
import java.util.Optional
import scala.concurrent.Future

abstract class ScanStoreTest extends StoreTest with HasExecutionContext with StoreErrors {

  "ScanStore" should {

    "getTotalCoinBalance" should {

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

    }

    "getTotalRewardsCollectedEver" should {

      "return the sum of reward amounts (ValidatorReward & AppReward)" in {
        val validatorRewards = Seq(
          9.5,
          11.5,
        )
        val appRewards = Seq(
          11.25,
          9.75,
        )
        for {
          store <- mkStore(user1)
          _ <- Future.traverse(appRewards.zip(validatorRewards).zipWithIndex) {
            case ((appAmount, validatorAmount), round) =>
              dummyDomain.exercise(
                coinRules(),
                Some(ccApiCodegen.coin.CoinRules.TEMPLATE_ID),
                Transfer.choice.name,
                mkCoinRulesTransfer(user1, 1.0),
                mkTransferResult(
                  round.toLong,
                  1.0,
                  holdingFee,
                  inputAppRewardAmount = appAmount,
                  inputValidatorRewardAmount = validatorAmount,
                ),
              )(store.multiDomainAcsStore)
          }
        } yield {
          eventually() {
            store
              .getTotalRewardsCollectedEver()
              .futureValue shouldBe validatorRewards.sum + appRewards.sum
          }
        }
      }

    }

    "getRewardsCollectedInRound" should {

      "return the sum of reward amounts (ValidatorReward & AppReward) up to the round" in {
        val validatorRewards = Seq(
          9.5,
          11.5,
          33.3,
        )
        val appRewards = Seq(
          11.25,
          9.75,
          33.3,
        )
        for {
          store <- mkStore(user1)
          _ <- Future.traverse(appRewards.zipWithIndex) { case (amount, round) =>
            dummyDomain.exercise(
              coinRules(),
              Some(ccApiCodegen.coin.CoinRules.TEMPLATE_ID),
              Transfer.choice.name,
              mkCoinRulesTransfer(user1, amount),
              mkTransferResult(
                round.toLong,
                amount,
                holdingFee,
                inputAppRewardAmount = amount,
              ),
            )(store.multiDomainAcsStore)
          }
          _ <- Future.traverse(validatorRewards.zipWithIndex) { case (amount, round) =>
            dummyDomain.exercise(
              coinRules(),
              Some(ccApiCodegen.coin.CoinRules.TEMPLATE_ID),
              Transfer.choice.name,
              mkCoinRulesTransfer(user1, amount),
              mkTransferResult(
                round.toLong,
                amount,
                holdingFee,
                inputValidatorRewardAmount = amount,
              ),
            )(store.multiDomainAcsStore)
          }
        } yield {
          eventually() {
            store.getRewardsCollectedInRound(1).futureValue shouldBe validatorRewards(
              1
            ) + appRewards(1)
          }
        }
      }

    }

    "getCoinConfigForRound" should {

      "return the coin OpenMiningRoundLogEntry for the round" in {
        val wanted = openMiningRound(svcParty, round = 2, coinPrice = 2.0)
        val unwanted = openMiningRound(svcParty, round = 3, coinPrice = 3.0)
        for {
          store <- mkStore()
          wantedTx <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          unwantedTx <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          transactionTreeSource.addTree(wantedTx)
          transactionTreeSource.addTree(unwantedTx)
          eventually() {
            val logEntry = store.getCoinConfigForRound(round = 2).futureValue
            logEntry.indexRecord match {
              case TxLogIndexRecord.OpenMiningRoundIndexRecord(_, _, _, round) =>
                round should be(wanted.payload.round.number)
              case x =>
                fail(s"Index record was not an OpenMiningRoundIndexRecord but a $x")
            }
            numeric(logEntry.coinCreateFee) should be(
              numeric(
                wanted.payload.transferConfigUsd.createFee.fee.divide(wanted.payload.coinPrice)
              )
            )
          }
        }
      }

    }

    "getRoundOfLatestData" should {

      "return the latest closed round" in {
        val wantedOpen = openMiningRound(svcParty, round = 2, coinPrice = 2.0)
        val closed = closedMiningRound(svcParty, round = 2)
        val unwantedOpen = openMiningRound(svcParty, round = 3, coinPrice = 3.0)
        for {
          store <- mkStore()
          wantedOpenTx <- dummyDomain.create(
            wantedOpen,
            txEffectiveAt = Instant.ofEpochSecond(1000),
          )(
            store.multiDomainAcsStore
          )
          unwantedOpenTx <- dummyDomain.create(
            unwantedOpen,
            txEffectiveAt = Instant.ofEpochSecond(2000),
          )(store.multiDomainAcsStore)
          closeTime = Instant.ofEpochSecond(1500)
          closedTx <- dummyDomain.create(closed, txEffectiveAt = closeTime)(
            store.multiDomainAcsStore
          )
        } yield {
          transactionTreeSource.addTree(wantedOpenTx)
          transactionTreeSource.addTree(unwantedOpenTx)
          transactionTreeSource.addTree(closedTx)
          eventually() {
            val (round, effectiveAt) = store.getRoundOfLatestData().futureValue
            round should be(2)
            effectiveAt should be(closeTime)
          }
        }
      }

      "fail if there's no closed round" in {
        val open = openMiningRound(svcParty, round = 2, coinPrice = 2.0)
        for {
          store <- mkStore()
          wantedOpenTx <- dummyDomain.create(open)(store.multiDomainAcsStore)
        } yield {
          transactionTreeSource.addTree(wantedOpenTx)
          eventually() {
            val failure = store.getRoundOfLatestData().failed.futureValue
            failure.getMessage should be(txLogNotFound().getMessage)
          }
        }
      }

      "fail if there's a closed round but no corresponding open round" in {
        val closed = closedMiningRound(svcParty, round = 2)
        val openAfter = openMiningRound(svcParty, round = 3, coinPrice = 3.0)
        for {
          store <- mkStore()
          openAfterTx <- dummyDomain.create(
            openAfter,
            txEffectiveAt = Instant.ofEpochSecond(2000),
          )(store.multiDomainAcsStore)
          closeTime = Instant.ofEpochSecond(1500)
          closedTx <- dummyDomain.create(closed, txEffectiveAt = closeTime)(
            store.multiDomainAcsStore
          )
        } yield {
          transactionTreeSource.addTree(openAfterTx)
          transactionTreeSource.addTree(closedTx)
          eventually() {
            val failure = store.getRoundOfLatestData().failed.futureValue
            failure.getMessage should be(txLogNotFound().getMessage)
          }
        }
      }

    }

    // tests look the same for both getTopProvidersByAppRewards & getTopValidatorsByValidatorRewards
    type Round = Long
    type Limit = Int
    type Amount = Double
    def topProvidersTest(
        getTopProviders: (ScanStore, Round, Limit) => Future[Seq[(PartyId, BigDecimal)]],
        mkTransferResultForTest: (Amount, Round) => DamlRecord,
    ) = {
      val asOfEndOfRound = 5L
      val providerRewardRounds = Seq(
        // (provider, amount, round)
        // 1
        (userParty(1), 4.0, 1),
        (userParty(1), 4.0, 2),
        (userParty(1), 666.0, 200), // excluded
        // 2
        (userParty(2), 4.0, 1),
        // 3
        (userParty(3), 4.0, 1),
        (userParty(3), 4.0, 2),
        (userParty(3), 4.0, 3),
        // 4
        (userParty(4), 4.0, 10000), // excluded
      )
      val open = openMiningRound(svcParty, round = asOfEndOfRound, coinPrice = 2.0)
      val closed = closedMiningRound(svcParty, round = asOfEndOfRound)
      for {
        store <- mkStore()
        openTx <- dummyDomain.create(open)(store.multiDomainAcsStore)
        closedTx <- dummyDomain.create(closed)(store.multiDomainAcsStore)
        rewardTxs <- Future.traverse(providerRewardRounds) { case (provider, amount, round) =>
          dummyDomain.exercise(
            coinRules(),
            Some(ccApiCodegen.coin.CoinRules.TEMPLATE_ID),
            Transfer.choice.name,
            mkCoinRulesTransfer(provider, amount),
            mkTransferResultForTest(
              amount,
              round.toLong,
            ),
          )(store.multiDomainAcsStore)
        }
      } yield {
        transactionTreeSource.addTree(openTx)
        transactionTreeSource.addTree(closedTx)
        rewardTxs.foreach(transactionTreeSource.addTree)
        eventually() {
          getTopProviders(store, asOfEndOfRound, 2).futureValue shouldBe Seq(
            userParty(3) -> BigDecimal(4.0 * 3),
            userParty(1) -> BigDecimal(4.0 * 2),
          )
        }
      }
    }

    "getTopProvidersByAppRewards" should {

      "return the top `limit` providers by app rewards" in {
        topProvidersTest(
          (store, round, limit) => store.getTopProvidersByAppRewards(round, limit),
          (amount, round) =>
            mkTransferResult(
              round = round,
              changeToInitialAmountAsOfRoundZero = 1.0,
              changeToHoldingFeesRate = 1.0,
              inputAppRewardAmount = amount,
            ),
        )
      }

    }

    "getTopValidatorsByValidatorRewards" should {

      "return the top `limit` providers by app rewards" in {
        topProvidersTest(
          (store, round, limit) => store.getTopValidatorsByValidatorRewards(round, limit),
          (amount, round) =>
            mkTransferResult(
              round = round,
              changeToInitialAmountAsOfRoundZero = 1.0,
              changeToHoldingFeesRate = 1.0,
              inputValidatorRewardAmount = amount,
            ),
        )
      }

    }

    "getTopValidatorsByPurchasedTraffic" should {

      "return the top `limit` providers by purchased traffic" in {
        val asOfEndOfRound = 5L
        val trafficPurchaseTrees = Seq(
          // user 1
          coinRulesBuyExtraTrafficTransaction(
            receiver = userParty(1),
            round = 1,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          coinRulesBuyExtraTrafficTransaction(
            receiver = userParty(1),
            round = 2,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          coinRulesBuyExtraTrafficTransaction(
            receiver = userParty(1),
            round = 3,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          // user 2
          coinRulesBuyExtraTrafficTransaction(
            receiver = userParty(2),
            round = 1,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          // user 3
          coinRulesBuyExtraTrafficTransaction(
            receiver = userParty(3),
            round = 1,
            extraTraffic = 4,
            ccSpent = 3.0,
          )(_),
          coinRulesBuyExtraTrafficTransaction(
            receiver = userParty(3),
            round = 2,
            extraTraffic = 4,
            ccSpent = 3.0,
          )(_),
          // user 4
          coinRulesBuyExtraTrafficTransaction(
            receiver = userParty(4),
            round = 1000, // excluded
            extraTraffic = 400000,
            ccSpent = 2222.0,
          )(_),
        )
        val open = openMiningRound(svcParty, round = asOfEndOfRound, coinPrice = 2.0)
        val closed = closedMiningRound(svcParty, round = asOfEndOfRound)
        for {
          store <- mkStore()
          openTx <- dummyDomain.create(open)(store.multiDomainAcsStore)
          closedTx <- dummyDomain.create(closed)(store.multiDomainAcsStore)
          trafficPurchaseUpdates <- Future.traverse(trafficPurchaseTrees)(
            dummyDomain.ingest(_)(store.multiDomainAcsStore)
          )
        } yield {
          transactionTreeSource.addTree(openTx)
          transactionTreeSource.addTree(closedTx)
          trafficPurchaseUpdates.foreach(transactionTreeSource.addTree)
          eventually() {
            store
              .getTopValidatorsByPurchasedTraffic(asOfEndOfRound, limit = 2)
              .futureValue shouldBe Seq(
              HttpScanAppClient.ValidatorPurchasedTraffic(
                validator = userParty(1),
                numPurchases = 3,
                totalTrafficPurchased = 4 * 3,
                totalCcSpent = 2.0 * 3,
                lastPurchasedInRound = 3,
              ),
              HttpScanAppClient.ValidatorPurchasedTraffic(
                validator = userParty(3),
                numPurchases = 2,
                totalTrafficPurchased = 4 * 2,
                totalCcSpent = 3.0 * 2,
                lastPurchasedInRound = 2,
              ),
            )
          }
        }
      }

    }

    "lookupCoinRules" should {

      "find the latest coin rules" in {
        val cr = coinRules()
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .lookupCoinRules()
              .futureValue
              .map(_.contract) should be(Some(cr))
          }
        }
      }

    }

    "lookupCnsRules" should {
      "find the latest CNS rules" in {
        val cr = cnsRules()
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .lookupCnsRules()
              .futureValue
              .map(_.contract) should be(Some(cr))
          }
        }
      }
    }

    "lookupCoinRulesV1Test" should {

      "find the coin rules" in {
        val cr = coinRulesV1Test(1)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .lookupCoinRulesV1Test()
              .futureValue
              .map(_.contract) should be(Some(cr))
          }
        }
      }

    }

    "lookupValidatorTraffic" should {

      "return the validator traffic of the wanted validator" in {
        val wanted = validatorTraffic(userParty(1))
        val unwanted = validatorTraffic(userParty(2))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .lookupValidatorTraffic(userParty(1))
              .futureValue should be(Some(wanted))
          }
        }
      }

    }

    "listImportCrates" should {

      "return all import crates of a receiver" in {
        val wanted1 = importCrate(userParty(1), 1)
        val unwanted = importCrate(userParty(2), 1)
        val wanted2 = importCrate(userParty(1), 2)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted1)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wanted2)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .listImportCrates(userParty(1))
              .futureValue
              .map(_.contract) should contain theSameElementsAs Set(wanted1, wanted2)
          }
        }
      }

    }

    "findFeaturedAppRight" should {

      "return the FeaturedAppRight of the wanted provider" in {
        val wanted = featuredAppRight(userParty(1))
        val unwanted = featuredAppRight(userParty(2))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          eventually() {
            store
              .findFeaturedAppRight(dummyDomain, userParty(1))
              .futureValue should be(Some(wanted))
          }
        }
      }
    }

    "listRecentActivity" should {
      "return the most recent activities" in {
        val limit = 10
        val nrActivities = 11
        val activities = (1 to nrActivities).map { i =>
          TxLogEntry.RecentActivityLogEntry(
            TxLogIndexRecord
              .RecentActivityIndexRecord(
                s"$i",
                s"$i",
                domainId = dummyDomain,
              ),
            provider = user1.toProtoPrimitive,
            sender = user1.toProtoPrimitive,
            receiver = user1.toProtoPrimitive,
            amount = BigDecimal(i),
            coinPrice = BigDecimal(1.0),
          )
        }.toList
        def stripEventId(activity: TxLogEntry.RecentActivityLogEntry) =
          activity.copy(indexRecord = activity.indexRecord.copy(eventId = ""))
        // recent activity returns most recent 10 activities
        val expectedActivities = activities.reverse.take(limit).toList

        def transferFromActivity(
            store: ScanStore,
            coinRulesContract: Contract[coinCodegen.CoinRules.ContractId, coinCodegen.CoinRules],
            activity: TxLogEntry.RecentActivityLogEntry,
            offset: String,
        ) = {
          dummyDomain
            .exercise(
              coinRulesContract,
              interfaceId = Some(ccApiCodegen.coin.CoinRules.TEMPLATE_ID),
              Transfer.choice.name,
              mkCoinRulesTransfer(user1, activity.amount.toDouble),
              mkTransferResult(
                round = 2,
                changeToInitialAmountAsOfRoundZero = 0,
                changeToHoldingFeesRate = holdingFee,
                inputCoinAmount = activity.amount.toDouble,
                coinPrice = activity.coinPrice.toDouble,
              ),
              offset,
            )(
              store.multiDomainAcsStore
            )
            .map { result =>
              transactionTreeSource.addTree(result)
              ()
            }
        }

        for {
          store <- mkStore()
          coinRulesContract = coinRules()
          _ <- activities.foldLeft(Future.successful(())) { (f, activity) =>
            f.flatMap { _ =>
              transferFromActivity(store, coinRulesContract, activity, activity.indexRecord.offset)
            }
          }
        } yield {
          eventually() {
            store
              .listRecentActivity(limit)
              .futureValue
              .map(stripEventId)
              .toList should be(expectedActivities.map(stripEventId))
          }
        }
      }
    }
  }

  protected def mkStore(endUserParty: PartyId = svcParty): Future[ScanStore]

  protected lazy val transactionTreeSource = TxLogStore.TransactionTreeSource.ForTesting()

  private val holdingFee = 1.0
  private lazy val user1 = userParty(1)
  private val enabledChoices = CNNodeUtil.defaultEnabledChoices
  private val schedule: scheduleCodegen.Schedule[Instant, CoinConfig[USD]] =
    CNNodeUtil.defaultCoinConfigSchedule(
      NonNegativeFiniteDuration(Duration.ofMinutes(10)),
      10,
      dummyDomain,
    )

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

  private def cnsRules() = {
    val templateId = cnsCodegen.CnsRules.TEMPLATE_ID

    val template = new cnsCodegen.CnsRules(
      svcParty.toProtoPrimitive,
      new cnsCodegen.CnsRulesConfig(
        new RelTime(1_000_000),
        new RelTime(1_000_000),
        new java.math.BigDecimal(1.0).setScale(10),
      ),
    )
    Contract(
      identifier = templateId,
      contractId = new cnsCodegen.CnsRules.ContractId(nextCid()),
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
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputCoinAmount: Double,
      coinPrice: Double,
  ) = new ccApiCodegen.coin.TransferSummary(
    new java.math.BigDecimal(inputAppRewardAmount),
    new java.math.BigDecimal(inputValidatorRewardAmount),
    new java.math.BigDecimal(inputCoinAmount),
    new java.math.BigDecimal(0.0),
    java.util.List.of(new java.math.BigDecimal(0.0)),
    new java.math.BigDecimal(0.0),
    new java.math.BigDecimal(0.0),
    new java.math.BigDecimal(coinPrice),
    new java.math.BigDecimal(changeToInitialAmountAsOfRoundZero),
    new java.math.BigDecimal(changeToHoldingFeesRate),
  )

  private def mkTransferResult(
      round: Long,
      changeToInitialAmountAsOfRoundZero: Double,
      changeToHoldingFeesRate: Double,
      inputAppRewardAmount: Double = 0.0,
      inputValidatorRewardAmount: Double = 0.0,
      inputCoinAmount: Double = 0.0,
      coinPrice: Double = 0.0,
  ) =
    new ccApiCodegen.coin.TransferResult(
      new ccApiCodegen.round.Round(round),
      mkTransferSummary(
        changeToInitialAmountAsOfRoundZero,
        changeToHoldingFeesRate,
        inputAppRewardAmount,
        inputValidatorRewardAmount,
        inputCoinAmount,
        coinPrice,
      ),
      java.util.List.of(),
      Optional.empty(),
    ).toValue

  private def coinRulesBuyExtraTrafficTransaction(
      receiver: PartyId,
      round: Long,
      extraTraffic: Long,
      ccSpent: Double,
  )(offset: String) = {
    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val coinRulesCid = nextCid()

    val validatorTrafficCid = new ValidatorTraffic.ContractId(validContractId(round.toInt))

    val transfer = exercisedEvent(
      coinRulesCid,
      coinCodegen.CoinRules.TEMPLATE_ID,
      Some(ccApiCodegen.coin.CoinRules.TEMPLATE_ID),
      Transfer.choice.name,
      consuming = false,
      mkCoinRulesTransfer(receiver, ccSpent),
      mkTransferResult(round, ccSpent, holdingFee),
    )

    mkExerciseTx(
      offset,
      exercisedEvent(
        coinRulesCid,
        coinCodegen.CoinRules.TEMPLATE_ID,
        None,
        coinCodegen.CoinRules.CHOICE_CoinRules_BuyExtraTraffic.name,
        consuming = false,
        new coinCodegen.CoinRules_BuyExtraTraffic(
          java.util.List.of(),
          new PaymentTransferContext(
            new ccApiCodegen.coin.CoinRules.ContractId(coinRulesCid),
            mkTransferContext(),
          ),
          receiver.toProtoPrimitive,
          new daTypes.either.Right(validatorTrafficCid),
          extraTraffic,
        ).toValue,
        CoinRules_BuyExtraTraffic.resToValue(
          new com.daml.network.codegen.java.da.types.Tuple2(
            validatorTrafficCid,
            Optional.empty[com.daml.network.codegen.java.cc.api.v1.coin.Coin.ContractId](),
          )
        ),
      ),
      Seq(
        transfer, // the second child has to be a transfer, we don't care what the first is for store's purposes
        transfer,
      ),
    )
  }

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

  private def openMiningRound(svc: PartyId, round: Long, coinPrice: Double) = {
    val template = new roundCodegen.OpenMiningRound(
      svc.toProtoPrimitive,
      new ccApiCodegen.round.Round(round),
      new java.math.BigDecimal(coinPrice),
      Instant.now(),
      Instant.now().plusSeconds(600),
      new RelTime(1_000_000),
      CNNodeUtil.defaultTransferConfig(10, holdingFee),
      CNNodeUtil.issuanceConfig(10.0, 10.0, 10.0),
      new RelTime(1_000_000),
    )

    Contract(
      roundCodegen.OpenMiningRound.TEMPLATE_ID,
      new roundCodegen.OpenMiningRound.ContractId(n.toString),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def closedMiningRound(svc: PartyId, round: Long) = {
    val template = new roundCodegen.ClosedMiningRound(
      svc.toProtoPrimitive,
      new ccApiCodegen.round.Round(round),
      new java.math.BigDecimal(1),
      new java.math.BigDecimal(1),
      new java.math.BigDecimal(1),
    )

    Contract(
      roundCodegen.ClosedMiningRound.TEMPLATE_ID,
      new roundCodegen.ClosedMiningRound.ContractId(n.toString),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
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

  private def coinRulesV1Test(n: Int) = {
    val template = new CoinRulesV1Test(
      svcParty.toProtoPrimitive,
      CNNodeUtil.defaultCoinConfigSchedule(
        NonNegativeFiniteDuration(Duration.ofMinutes(10)),
        10,
        dummyDomain,
      ),
      CNNodeUtil.defaultEnabledChoices,
      true,
      false,
    )
    Contract(
      CoinRulesV1Test.TEMPLATE_ID,
      new CoinRulesV1Test.ContractId(n.toString),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def validatorTraffic(validatorParty: PartyId) = {
    val template = new ValidatorTraffic(
      svcParty.toProtoPrimitive,
      validatorParty.toProtoPrimitive,
      3,
      1,
      numeric(1),
      numeric(1),
      Instant.now(),
      domain,
    )
    Contract(
      ValidatorTraffic.TEMPLATE_ID,
      new ValidatorTraffic.ContractId(n.toString),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def importCrate(receiver: PartyId, n: Int) = {
    val template = new ImportCrate(
      svcParty.toProtoPrimitive,
      receiver.toProtoPrimitive,
      new IP_Coin(coinTemplate(n.toDouble, receiver)),
    )
    Contract(
      ImportCrate.TEMPLATE_ID,
      new ImportCrate.ContractId(s"${receiver.toProtoPrimitive}::$n"),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def featuredAppRight(providerParty: PartyId) = {
    val template = new FeaturedAppRight(svcParty.toProtoPrimitive, providerParty.toProtoPrimitive)
    Contract(
      FeaturedAppRight.TEMPLATE_ID,
      new FeaturedAppRight.ContractId(providerParty.toProtoPrimitive),
      template,
      ContractMetadata.Empty(),
      protobuf.Any.getDefaultInstance,
    )
  }

  private def coinTemplate(amount: Double, owner: PartyId) = {
    new Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      expiringAmount(amount),
    )
  }

  private def expiringAmount(amount: Double) = new cc.fees.ExpiringAmount(
    numeric(amount),
    new cc.api.v1.round.Round(0L),
    new cc.fees.RatePerRound(numeric(amount)),
  )

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
}

class InMemoryScanStoreTest extends ScanStoreTest {
  override protected def mkStore(endUserParty: PartyId): Future[InMemoryScanStore] = {
    val store = new InMemoryScanStore(
      endUserParty,
      ScanAppBackendConfig(
        storage = CNDbConfig.Memory(),
        svUser = endUserParty.toProtoPrimitive,
        enableCoinRulesUpgrade = true,
        participantClient = null,
        domains = new ScanDomainConfig(DomainConfig(DomainAlias.tryCreate(domain))),
      ),
      loggerFactory,
      transactionTreeSource,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }
}

class DbScanStoreTest
    extends ScanStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(endUserParty: PartyId): Future[DbScanStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        Seq(
          "dar/canton-coin-0.1.1.dar",
          "dar/canton-name-service-0.1.0.dar",
        )
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbScanStore(
      endUserParty,
      storage,
      ScanAppBackendConfig(
        storage = CNDbConfig.Memory(), // Note: this field is not used by the store
        svUser = endUserParty.toProtoPrimitive,
        enableCoinRulesUpgrade = true,
        participantClient = null,
        domains = new ScanDomainConfig(DomainConfig(DomainAlias.tryCreate(domain))),
      ),
      loggerFactory,
      transactionTreeSource,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}
