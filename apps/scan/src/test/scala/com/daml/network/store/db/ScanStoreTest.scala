package com.daml.network.store.db

import com.daml.ledger.javaapi.data.{DamlRecord, Unit as damlUnit}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.Coin
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.coinimport.importpayload.IP_Coin
import com.daml.network.codegen.java.cc.coinrules.BuyMemberTrafficResult
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cc.validatorlicense.FaucetState
import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cn.cns.CnsEntry
import com.daml.network.codegen.java.cn.{cometbft as cometbftCodegen, svcrules as svcrulesCodegen}
import com.daml.network.codegen.java.cn.svc.globaldomain as globaldomainCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.{DarResources, RetryProvider}
import com.daml.network.history.{CoinExpire, LockedCoinExpireCoin, Transfer}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.store.{
  OpenMiningRoundTxLogEntry,
  ReceiverAmount,
  SenderAmount,
  SortOrder,
  TransferTxLogEntry,
}
import com.daml.network.scan.store.ScanStore
import com.daml.network.scan.store.db.{DbScanStore, ScanAggregatesReader, ScanAggregator}
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{PageLimit, StoreErrors, StoreTest}
import com.daml.network.store.MultiDomainAcsStore.ContractState.Assigned
import com.daml.network.util.CNNodeUtil.damlDecimal
import com.daml.network.util.{
  Contract,
  ContractWithState,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}

import java.time.Instant
import java.util.{Collections, Optional}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import scala.reflect.ClassTag
import com.digitalasset.canton.util.MonadUtil
import scala.concurrent.ExecutionContext

abstract class ScanStoreTest extends StoreTest with HasExecutionContext with StoreErrors {

  "ScanStore" should {

    "getTotalCoinBalance" should {

      "return correct total coin balance for the round where the transfer happened and for the rounds before and after" in {
        val coinAmount = 100.0
        // For aggregation to work correctly, all closed mining rounds for totals have to exist.
        val closedRounds = (0 to 3).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }
        for {
          store <- mkStore()
          coinRulesContract = coinRules()
          _ <- dummyDomain.exercise(
            coinRulesContract,
            interfaceId = Some(cc.coinrules.CoinRules.TEMPLATE_ID),
            Transfer.choice.name,
            mkCoinRulesTransfer(user1, coinAmount),
            mkTransferResult(
              round = 2,
              inputAppRewardAmount = 0,
              inputCoinAmount = coinAmount,
              inputValidatorRewardAmount = 0,
              balanceChanges = Map(
                user1.toProtoPrimitive -> new cc.coinrules.BalanceChange(
                  BigDecimal(coinAmount).bigDecimal,
                  BigDecimal(holdingFee).bigDecimal,
                )
              ),
              coinPrice = 0.0005,
            ),
            "011",
          )(
            store.multiDomainAcsStore
          )
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getTotalCoinBalance(1).futureValue shouldBe (0.0)
          // 100.0 is the initial amount as of round 0, so at the end of round 2 the holding fee was applied three times
          store.getTotalCoinBalance(2).futureValue shouldBe (coinAmount - 3 * holdingFee)
          store.getTotalCoinBalance(3).futureValue shouldBe (coinAmount - 4 * holdingFee)
        }
      }

      "return correct total coin balance for the round where the coin expired and for the rounds before and after" in {
        val coinRound1 = 100.0
        val changeToInitialAmountAsOfRoundZero = -50.0
        // For aggregation to work correctly, all closed mining rounds for totals have to exist.
        val closedRounds = (0 to 3).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- dummyDomain.ingest(mintTransaction(user1, coinRound1, 1, holdingFee))(
            store.multiDomainAcsStore
          )
          coinContract = coin(user1, coinRound1, 1, holdingFee)
          _ <- dummyDomain.exercise(
            coinContract,
            interfaceId = Some(cc.coin.Coin.TEMPLATE_ID),
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
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getTotalCoinBalance(1).futureValue shouldBe (coinRound1 - 1 * holdingFee)
          store
            .getTotalCoinBalance(2)
            .futureValue shouldBe (coinRound1 - 2 * holdingFee + changeToInitialAmountAsOfRoundZero - 3 * holdingFee)
          store
            .getTotalCoinBalance(3)
            .futureValue shouldBe (coinRound1 - 3 * holdingFee + changeToInitialAmountAsOfRoundZero - 4 * holdingFee)
        }
      }

      "return correct total coin balance for the round where the locked coin expired and for the rounds before and after" in {
        val coinRound1 = 100.0
        val changeToInitialAmountAsOfRoundZero = -50.0
        // For aggregation to work correctly, all closed mining rounds for totals have to exist.
        val closedRounds = (0 to 3).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- dummyDomain.ingest(mintTransaction(user1, coinRound1, 1, holdingFee))(
            store.multiDomainAcsStore
          )
          coinContract = lockedCoin(user1, coinRound1, 1, holdingFee)
          _ <- dummyDomain.exercise(
            coinContract,
            interfaceId = Some(cc.coin.LockedCoin.TEMPLATE_ID),
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
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getTotalCoinBalance(1).futureValue shouldBe (coinRound1 - 1 * holdingFee)
          store
            .getTotalCoinBalance(2)
            .futureValue shouldBe (coinRound1 - 2 * holdingFee + changeToInitialAmountAsOfRoundZero - 3 * holdingFee)
          store
            .getTotalCoinBalance(3)
            .futureValue shouldBe (coinRound1 - 3 * holdingFee + changeToInitialAmountAsOfRoundZero - 4 * holdingFee)
        }
      }

      "return correct total coin balance for the round where the mint happened and for the rounds before and after" in {
        val mintAmount = 100.0
        // For aggregation to work correctly, all closed mining rounds for totals have to exist.
        val closedRounds = (0 to 3).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- dummyDomain.ingest(mintTransaction(user1, mintAmount, 2, holdingFee))(
            store.multiDomainAcsStore
          )
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getTotalCoinBalance(1).futureValue shouldBe (0.0)
          // The coin is minted at round 2, so at the end of that round it's already incurring 1 x holding fee
          store.getTotalCoinBalance(2).futureValue shouldBe (mintAmount - 1 * holdingFee)
          store.getTotalCoinBalance(3).futureValue shouldBe (mintAmount - 2 * holdingFee)
        }
      }

    }

    "getWalletBalance" should {
      "return correct wallet balance for the round where the transfer happened and for the rounds before and after" in {
        val keptCoinAmount = 60.0
        val sentCoinAmount = 40.0
        val coinAmount = keptCoinAmount + sentCoinAmount
        val lastClosedRound = 3
        val closedRounds = (0 to lastClosedRound).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          coinRulesContract = coinRules()
          _ <- dummyDomain.exercise(
            coinRulesContract,
            interfaceId = Some(cc.coinrules.CoinRules.TEMPLATE_ID),
            Transfer.choice.name,
            mkCoinRulesTransfer(user1, coinAmount),
            mkTransferResult(
              round = 2,
              inputAppRewardAmount = 0,
              inputCoinAmount = coinAmount,
              inputValidatorRewardAmount = 0,
              balanceChanges = Map(
                user1.toProtoPrimitive -> new cc.coinrules.BalanceChange(
                  BigDecimal(keptCoinAmount).bigDecimal,
                  BigDecimal(holdingFee).bigDecimal,
                ),
                user2.toProtoPrimitive -> new cc.coinrules.BalanceChange(
                  BigDecimal(sentCoinAmount).bigDecimal,
                  BigDecimal(holdingFee).bigDecimal,
                ),
              ),
              coinPrice = 0.0005,
            ),
            "011",
          )(
            store.multiDomainAcsStore
          )
          _ <- MonadUtil.sequentialTraverse(closedRounds) { closed =>
            dummyDomain.create(closed)(
              store.multiDomainAcsStore
            )
          }
          _ <- store.aggregate()
        } yield {
          // 100.0 is the initial amount as of round 0, so at the end of round 2 the holding fee was applied three times
          forEvery(
            Table(
              ("user", "coin amount"),
              (user1, keptCoinAmount),
              (user2, sentCoinAmount),
            )
          ) { (user, coinAmount) =>
            store.getWalletBalance(user, 1).futureValue shouldBe (0.0) withClue "at round 1"
            store
              .getWalletBalance(user, 2)
              .futureValue shouldBe (coinAmount - 3 * holdingFee) withClue "at round 2"
            store
              .getWalletBalance(user, 3)
              .futureValue shouldBe (coinAmount - 4 * holdingFee) withClue "at round 3"
            val failure = store.getWalletBalance(user, lastClosedRound + 1L).failed.futureValue
            failure.getMessage should be(roundNotAggregated().getMessage)
          }
        }
      }

      "accumulate on coin expiry, locked coin expiry, and minting" in {
        import MonadUtil.sequentialTraverse_
        val mintAmount1 = 60.0
        val mintAmount2 = 40.0
        val expireAmount1 = -24.0
        val expireAmount2 = -18.0
        for {
          store <- mkStore()
          coinRulesContract = coinRules()
          // the round where the mint happened and for the rounds before and after
          _ <- sequentialTraverse_(Seq(user1 -> mintAmount1, user2 -> mintAmount2)) {
            case (user, mintAmount) =>
              dummyDomain.ingest(
                mintTransaction(user, mintAmount, 2, holdingFee)
              )(store.multiDomainAcsStore)
          }
          // "the round where the coin expired and for the rounds before and after"
          coinContract = coin(user1, mintAmount1, 2, holdingFee)
          _ <- dummyDomain.exercise(
            coinContract,
            interfaceId = Some(cc.coin.Coin.TEMPLATE_ID),
            CoinExpire.choice.name,
            mkCoinExpire(),
            mkCoinExpireSummary(
              user1,
              4,
              expireAmount1,
              holdingFee,
            ),
            "011",
          )(store.multiDomainAcsStore)
          // "the round where the locked coin expired and for the rounds before and after
          lockedCoinContract = lockedCoin(user2, mintAmount2, 2, holdingFee)
          _ <- dummyDomain.exercise(
            lockedCoinContract,
            interfaceId = Some(cc.coin.LockedCoin.TEMPLATE_ID),
            LockedCoinExpireCoin.choice.name,
            mkLockedCoinExpireCoin(),
            mkCoinExpireSummary(
              user2,
              6,
              expireAmount2,
              holdingFee,
            ),
            "012",
          )(
            store.multiDomainAcsStore
          )
          closedRounds = (0 to 7).map { round =>
            closedMiningRound(svcParty, round = round.toLong)
          }
          _ <- MonadUtil.sequentialTraverse(closedRounds) { closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore)
          }
          _ <- store.aggregate()
        } yield forEvery(
          Table(
            ("user", "round", "coin amount"),
            (user1, 1L, 0),
            (user2, 1L, 0),
            // check mints
            (user1, 2L, mintAmount1 - 1 * holdingFee),
            (user2, 2L, mintAmount2 - 1 * holdingFee),
            (user1, 3L, mintAmount1 - 2 * holdingFee),
            (user2, 3L, mintAmount2 - 2 * holdingFee),
            // check expire user1
            (user1, 4L, mintAmount1 - 3 * holdingFee + expireAmount1 - 5 * holdingFee),
            (user2, 4L, mintAmount2 - 3 * holdingFee),
            (user1, 5L, mintAmount1 - 4 * holdingFee + expireAmount1 - 6 * holdingFee),
            (user2, 5L, mintAmount2 - 4 * holdingFee),
            // check locked expire user2
            (user1, 6L, mintAmount1 - 5 * holdingFee + expireAmount1 - 7 * holdingFee),
            (user2, 6L, mintAmount2 - 5 * holdingFee + expireAmount2 - 7 * holdingFee),
            (user1, 7L, mintAmount1 - 6 * holdingFee + expireAmount1 - 8 * holdingFee),
            (user2, 7L, mintAmount2 - 6 * holdingFee + expireAmount2 - 8 * holdingFee),
          )
        ) { (user, round, coinAmount) =>
          store.getWalletBalance(user, round).futureValue shouldBe coinAmount
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
        val closedRounds = (0 to 1).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(appRewards.zip(validatorRewards).zipWithIndex) {
            case ((appAmount, validatorAmount), round) =>
              dummyDomain.exercise(
                coinRules(),
                Some(cc.coinrules.CoinRules.TEMPLATE_ID),
                Transfer.choice.name,
                mkCoinRulesTransfer(user1, 1.0),
                mkTransferResult(
                  round = round.toLong,
                  inputAppRewardAmount = appAmount,
                  inputValidatorRewardAmount = validatorAmount,
                  inputCoinAmount = 0,
                  balanceChanges = Map(),
                  coinPrice = 0.0005,
                ),
              )(store.multiDomainAcsStore)
          }
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store
            .getTotalRewardsCollectedEver()
            .futureValue shouldBe validatorRewards.sum + appRewards.sum
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
        val closedRounds = (0 to 2).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(appRewards.zipWithIndex) { case (amount, round) =>
            dummyDomain.exercise(
              coinRules(),
              Some(cc.coinrules.CoinRules.TEMPLATE_ID),
              Transfer.choice.name,
              mkCoinRulesTransfer(user1, amount),
              mkTransferResult(
                round = round.toLong,
                inputAppRewardAmount = amount,
                inputCoinAmount = 0,
                inputValidatorRewardAmount = 0,
                balanceChanges = Map(),
                coinPrice = 0.0005,
              ),
            )(store.multiDomainAcsStore)
          }
          _ <- MonadUtil.sequentialTraverse(validatorRewards.zipWithIndex) { case (amount, round) =>
            dummyDomain.exercise(
              coinRules(),
              Some(cc.coinrules.CoinRules.TEMPLATE_ID),
              Transfer.choice.name,
              mkCoinRulesTransfer(user1, amount),
              mkTransferResult(
                round = round.toLong,
                inputAppRewardAmount = 0,
                inputValidatorRewardAmount = amount,
                inputCoinAmount = 0,
                balanceChanges = Map(),
                coinPrice = 0.0005,
              ),
            )(store.multiDomainAcsStore)
          }
          _ = closedRounds.map(closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore).futureValue
          )
          _ <- store.aggregate()
        } yield {
          store.getRewardsCollectedInRound(1).futureValue shouldBe validatorRewards(
            1
          ) + appRewards(1)
        }
      }

    }

    "getCoinConfigForRound" should {

      "return the coin OpenMiningRoundTxLogEntry for the round" in {
        val wanted = openMiningRound(svcParty, round = 2, coinPrice = 2.0)
        val unwanted = openMiningRound(svcParty, round = 3, coinPrice = 3.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          val logEntry = store.getCoinConfigForRound(round = 2).futureValue
          logEntry match {
            case omr: OpenMiningRoundTxLogEntry =>
              omr.round should be(wanted.payload.round.number)
            case x =>
              fail(s"Entry was not an OpenMiningRoundTxLogEntry but a $x")
          }
          numeric(logEntry.coinCreateFee) should be(
            numeric(
              wanted.payload.transferConfigUsd.createFee.fee.divide(wanted.payload.coinPrice)
            )
          )
        }
      }

    }

    "getRoundOfLatestData" should {

      "return the latest closed round" in {
        val closedBefore = (0 until 2).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }
        val closed = closedMiningRound(svcParty, round = 2)
        for {
          store <- mkStore()
          closeTime = Instant.ofEpochSecond(1500)
          _ <- MonadUtil.sequentialTraverse(closedBefore) { closed =>
            dummyDomain.create(closed, txEffectiveAt = closeTime)(
              store.multiDomainAcsStore
            )
          }
          _ <- dummyDomain.create(closed, txEffectiveAt = closeTime)(
            store.multiDomainAcsStore
          )
          _ <- store.aggregate()
        } yield {
          val (round, effectiveAt) = store.getRoundOfLatestData().futureValue
          round should be(2)
          effectiveAt should be(closeTime)
        }
      }

      "fail if there's no closed round" in {
        val open = openMiningRound(svcParty, round = 2, coinPrice = 2.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(open)(store.multiDomainAcsStore)
        } yield {
          val failure = store.getRoundOfLatestData().failed.futureValue
          failure.getMessage should be(roundNotAggregated().getMessage)
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
      val closed = closedMiningRound(svcParty, round = asOfEndOfRound)
      val closedBefore = (0 until asOfEndOfRound.toInt).map { round =>
        closedMiningRound(svcParty, round = round.toLong)
      }
      for {
        store <- mkStore()
        _ <- MonadUtil.sequentialTraverse(closedBefore) { closed =>
          dummyDomain.create(closed)(store.multiDomainAcsStore)
        }
        _ <- dummyDomain.create(closed)(store.multiDomainAcsStore)
        _ <- MonadUtil.sequentialTraverse(providerRewardRounds) { case (provider, amount, round) =>
          dummyDomain.exercise(
            coinRules(),
            Some(cc.coinrules.CoinRules.TEMPLATE_ID),
            Transfer.choice.name,
            mkCoinRulesTransfer(provider, amount),
            mkTransferResultForTest(
              amount,
              round.toLong,
            ),
          )(store.multiDomainAcsStore)
        }
        _ <- store.aggregate()
      } yield {
        getTopProviders(store, asOfEndOfRound, 2).futureValue shouldBe Seq(
          userParty(3) -> BigDecimal(4.0 * 3),
          userParty(1) -> BigDecimal(4.0 * 2),
        )
      }
    }

    "getTopProvidersByAppRewards" should {

      "return the top `limit` providers by app rewards" in {
        topProvidersTest(
          (store, round, limit) => store.getTopProvidersByAppRewards(round, limit),
          (amount, round) =>
            mkTransferResult(
              round = round,
              inputAppRewardAmount = amount,
              inputCoinAmount = 0,
              inputValidatorRewardAmount = 0,
              balanceChanges = Map(),
              coinPrice = 0.0005,
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
              inputAppRewardAmount = 0,
              inputValidatorRewardAmount = amount,
              inputCoinAmount = 0,
              balanceChanges = Map(),
              coinPrice = 0.0005,
            ),
        )
      }
    }

    "getTopValidatorsByPurchasedTraffic" should {

      "return the top `limit` providers by purchased traffic" in {
        val asOfEndOfRound = 5L
        val trafficPurchaseTrees = Seq(
          // user 1
          coinRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          coinRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 2,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          coinRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 3,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          // user 2
          coinRulesBuyMemberTrafficTransaction(
            provider = userParty(2),
            memberId = mkParticipantId("user-2"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          // user 3
          coinRulesBuyMemberTrafficTransaction(
            provider = userParty(3),
            memberId = mkParticipantId("user-3"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 3.0,
          )(_),
          coinRulesBuyMemberTrafficTransaction(
            provider = userParty(3),
            memberId = mkParticipantId("user-3"),
            round = 2,
            extraTraffic = 4,
            ccSpent = 3.0,
          )(_),
          // user 4
          coinRulesBuyMemberTrafficTransaction(
            provider = userParty(4),
            memberId = mkParticipantId("user-4"),
            round = 1000, // excluded
            extraTraffic = 400000,
            ccSpent = 2222.0,
          )(_),
        )
        val closedBefore = (0 until asOfEndOfRound.toInt).map { round =>
          closedMiningRound(svcParty, round = round.toLong)
        }
        val closed = closedMiningRound(svcParty, round = asOfEndOfRound)
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(closedBefore) { closed =>
            dummyDomain.create(closed)(store.multiDomainAcsStore)
          }
          _ <- dummyDomain.create(closed)(store.multiDomainAcsStore)
          _ <- MonadUtil.sequentialTraverse(trafficPurchaseTrees)(
            dummyDomain.ingest(_)(store.multiDomainAcsStore)
          )
          _ <- store.aggregate()
        } yield {
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

    "getTotalPurchasedMemberTraffic" should {

      "return the sum over all traffic contracts for the member" in {
        val namespace = Namespace(Fingerprint.tryCreate(s"dummy"))
        val goodMember = ParticipantId(Identifier.tryCreate("good"), namespace)
        val badMember = MediatorId(Identifier.tryCreate("bad"), namespace)
        val goodContracts = (1 to 3).map(n => memberTraffic(goodMember, n.toLong))
        val badContracts = (4 to 6).map(n => memberTraffic(badMember, n.toLong))
        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(
            goodContracts ++ badContracts
          )(
            dummyDomain.create(_)(store.multiDomainAcsStore)
          )
          result <- store.getTotalPurchasedMemberTraffic(
            goodMember,
            dummyDomain,
          )
        } yield result shouldBe (1 to 3).sum.toLong
      }

    }

    "lookupCoinRules" should {

      "find the latest coin rules" in {
        val cr = coinRules()
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupCoinRules()
            .futureValue
            .map(_.contract) should be(Some(cr))
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
          store
            .lookupCnsRules()
            .futureValue
            .map(_.contract) should be(Some(cr))
        }
      }
    }

    "lookupSvcRules" should {
      "find the latest Svc rules" in {
        val sr = svcRules()
        for {
          store <- mkStore()
          _ <- dummyDomain.create(sr)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupSvcRules()
            .futureValue
            .map(_.contract) should be(Some(sr))
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
          store
            .listImportCrates(userParty(1))
            .futureValue
            .map(_.contract) should contain theSameElementsAs Set(wanted1, wanted2)
        }
      }

    }

    "findFeaturedAppRight" should {

      "return the FeaturedAppRight of the wanted provider" in {
        val wanted = featuredAppRight(userParty(1))
        val unwanted = featuredAppRight(userParty(2))
        val expectedResult = Some(ContractWithState(wanted, Assigned(dummyDomain)))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          store
            .findFeaturedAppRight(userParty(1))
            .futureValue should be(expectedResult)
        }
      }
    }

    "listEntries" should {
      "list entries with prefix" in {
        for {
          store <- mkStore()
          unwantedContract = cnsEntry(1, "unwanted")
          wantedContract = cnsEntry(2, "wanted")
          wantedContract2 = cnsEntry(3, "wanted2")
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract2)(store.multiDomainAcsStore)
          expectedResult = Seq(
            ContractWithState(wantedContract, Assigned(dummyDomain)),
            ContractWithState(wantedContract2, Assigned(dummyDomain)),
          )
        } yield {
          store.listEntries("wanted").futureValue should be(
            expectedResult
          )
          store.listEntries("dummy").futureValue should be(
            Seq.empty
          )
        }
      }
    }

    "lookupEntryByName" should {
      "return None for no entry" in {
        for {
          store <- mkStore()
          result <- store.lookupEntryByName("nope")
        } yield result should be(None)
      }

      "return the entry with the exact name" in {
        for {
          store <- mkStore()
          unwantedContract = cnsEntry(1, "unwanted")
          wantedContract = cnsEntry(2, "wanted")
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
          expectedResult = Some(ContractWithState(wantedContract, Assigned(dummyDomain)))
        } yield {
          store.lookupEntryByName("wanted").futureValue should be(
            expectedResult
          )
        }
      }
    }

    "lookupEntryByParty" should {
      "return the first lexicographical entry of the user" in {
        for {
          store <- mkStore()
          unwantedContract = cnsEntry(1, "unwanted")
          bContract = cnsEntry(2, "b")
          aContract = cnsEntry(2, "a")
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(bContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(aContract)(store.multiDomainAcsStore)
          expectedResult = Some(ContractWithState(aContract, Assigned(dummyDomain)))
        } yield store.lookupEntryByParty(userParty(2)).futureValue should be(expectedResult)
      }
    }

    "listTransactions" should {
      "return the most recent txs in pages" in {
        val limit = 10
        val nrTransfers = 20
        val round = 1L
        val now = java.time.Instant.EPOCH
        val zero = BigDecimal(0)
        val txs: List[TransferTxLogEntry] = (1 to nrTransfers).map { i =>
          TransferTxLogEntry(
            offset = s"$i",
            eventId = s"$i",
            domainId = dummyDomain,
            date = Some(now),
            provider = user1,
            sender = Some(
              SenderAmount(
                user1,
                BigDecimal(i),
                zero,
                zero,
                zero,
                zero,
                zero,
                zero,
              )
            ),
            balanceChanges = Seq(),
            receivers = Seq(ReceiverAmount(user2, BigDecimal(i), zero)),
            round = round,
            coinPrice = BigDecimal(1.0),
          )
        }.toList
        def stripEventId(tx: TransferTxLogEntry) = tx.copy(eventId = "")
        val expectedFirstPage = txs.reverse.take(limit).toList
        val expectedSecondPage = txs.reverse.drop(limit).take(limit).toList

        def transferFromTransaction(
            store: ScanStore,
            coinRulesContract: Contract[cc.coinrules.CoinRules.ContractId, cc.coinrules.CoinRules],
            tx: TransferTxLogEntry,
            offset: String,
        ) = {
          val senderParty = tx.sender.getOrElse(throw txMissingField()).party
          val senderAmount = tx.sender.getOrElse(throw txMissingField()).inputCoinAmount
          val receiverParty = tx.receivers(0).party
          val receiverAmount = tx.receivers(0).amount
          dummyDomain
            .exercise(
              contract = coinRulesContract,
              interfaceId = Some(cc.coinrules.CoinRules.TEMPLATE_ID),
              choiceName = Transfer.choice.name,
              choiceArgument = mkCoinRules_Transfer(
                mkTransferInputOutput(
                  senderParty,
                  senderParty,
                  List(mkInputCoin()),
                  List(mkTransferOutput(receiverParty, receiverAmount)),
                )
              ),
              exerciseResult = mkTransferResult(
                round = round,
                inputAppRewardAmount = 0,
                inputCoinAmount = senderAmount.toDouble,
                inputValidatorRewardAmount = 0,
                balanceChanges = Map(),
                coinPrice = tx.coinPrice.toDouble,
              ),
              offset = offset,
            )(
              store.multiDomainAcsStore
            )
            .map(_ => ())
        }

        for {
          store <- mkStore()
          coinRulesContract = coinRules()
          _ <- txs.foldLeft(Future.successful(())) { (f, tx) =>
            f.flatMap { _ =>
              transferFromTransaction(
                store,
                coinRulesContract,
                tx,
                tx.offset,
              )
            }
          }
        } yield {
          val firstPageDescending = store
            .listByType[TransferTxLogEntry](None, SortOrder.Descending, limit)
            .futureValue
            .toList

          firstPageDescending
            .map(stripEventId) should be(
            expectedFirstPage
              .map(stripEventId)
          )
          val nextPageDescending = store
            .listByType[TransferTxLogEntry](
              Some(firstPageDescending.last.eventId),
              SortOrder.Descending,
              limit,
            )
            .futureValue
            .toList

          nextPageDescending
            .map(stripEventId) should be(
            expectedSecondPage
              .map(stripEventId)
          )

          val firstPageAscending = store
            .listByType[TransferTxLogEntry](None, SortOrder.Ascending, limit)
            .futureValue
            .toList

          firstPageAscending should be(nextPageDescending.reverse)

          val nextPageAscending = store
            .listByType[TransferTxLogEntry](
              Some(firstPageAscending.last.eventId),
              SortOrder.Ascending,
              limit,
            )
            .futureValue
            .toList

          nextPageAscending should be(firstPageDescending.reverse)
        }
      }
    }
  }

  protected def mkStore(
      serviceUserPrimaryParty: PartyId = user1,
      svcParty: PartyId = svcParty,
  ): Future[ScanStore]

  private lazy val user1 = userParty(1)
  private lazy val user2 = userParty(2)

  implicit class ScanStoreExt(store: ScanStore) {
    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def listByType[T](beginAfterEventId: Option[String], sortOrder: SortOrder, limit: Int)(implicit
        tag: ClassTag[T]
    ): Future[Seq[T]] = {
      store
        .listTransactions(beginAfterEventId, sortOrder, PageLimit.tryCreate(limit))
        .map(_.collect {
          case c if tag.runtimeClass.isInstance(c) => c.asInstanceOf[T]
        }.toSeq)
    }
  }
  private def mkInputCoin() = {
    new cc.coinrules.transferinput.InputCoin(
      new cc.coin.Coin.ContractId(nextCid())
    )
  }

  private def mkTransferOutput(
      receiver: PartyId,
      amount: BigDecimal,
      receiverFeeRatio: BigDecimal = BigDecimal(0.0),
  ): cc.coinrules.TransferOutput =
    new cc.coinrules.TransferOutput(
      receiver.toProtoPrimitive,
      receiverFeeRatio.bigDecimal,
      amount.bigDecimal,
      Optional.empty(),
    )

  private def mkTransfer(receiver: PartyId, amount: Double) =
    new cc.coinrules.Transfer(
      receiver.toProtoPrimitive,
      receiver.toProtoPrimitive,
      java.util.List.of(mkInputCoin()),
      java.util.List.of(mkTransferOutput(receiver, amount)),
      Optional.empty(),
    )

  private def mkTransferContext() = new cc.coinrules.TransferContext(
    new roundCodegen.OpenMiningRound.ContractId(nextCid()),
    java.util.Map.of(),
    java.util.Map.of(),
    Optional.empty(),
  )

  private def mkTransferInputOutput(
      sender: PartyId,
      provider: PartyId,
      transferInputs: List[cc.coinrules.TransferInput],
      transferOutputs: List[cc.coinrules.TransferOutput],
  ): cc.coinrules.Transfer =
    new cc.coinrules.Transfer(
      sender.toProtoPrimitive,
      provider.toProtoPrimitive,
      transferInputs.asJava,
      transferOutputs.asJava,
      Optional.empty(),
    )

  private def mkCoinRules_Transfer(transfer: cc.coinrules.Transfer) =
    new cc.coinrules.CoinRules_Transfer(
      transfer,
      mkTransferContext(),
    ).toValue

  private def mkCoinRulesTransfer(receiver: PartyId, amount: Double) =
    new cc.coinrules.CoinRules_Transfer(
      mkTransfer(receiver, amount),
      mkTransferContext(),
    ).toValue

  private def mkTransferSummary(
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputSvRewardAmount: Double,
      inputCoinAmount: Double,
      balanceChanges: Map[String, cc.coinrules.BalanceChange],
      coinPrice: Double,
  ) = new cc.coinrules.TransferSummary(
    damlDecimal(inputAppRewardAmount),
    damlDecimal(inputValidatorRewardAmount),
    damlDecimal(inputSvRewardAmount),
    damlDecimal(inputCoinAmount),
    balanceChanges.asJava,
    damlDecimal(0.0),
    java.util.List.of(damlDecimal(0.0)),
    damlDecimal(0.0),
    damlDecimal(0.0),
    damlDecimal(coinPrice),
    // the validator faucet amount is already included in the `inputValidatorRewardAmount`,
    // We'll set this here once we add support for showing faucet coupon rewards separately
    // from the usage-based validator rewards.
    // TODO(#9824): track faucet coupon inputs separately
    java.util.Optional.empty(),
  )

  private def mkTransferResult(
      round: Long,
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputCoinAmount: Double,
      balanceChanges: Map[String, cc.coinrules.BalanceChange],
      coinPrice: Double,
  ) =
    new cc.coinrules.TransferResult(
      new cc.types.Round(round),
      mkTransferSummary(
        inputAppRewardAmount,
        inputValidatorRewardAmount,
        // TODO (#9173): also test for sv rewards once the scan store supports them
        0.0,
        inputCoinAmount,
        balanceChanges,
        coinPrice,
      ),
      java.util.List.of(),
      Optional.empty(),
      Optional.empty(),
    ).toValue

  private def mkBuyMemberTrafficResult(
      round: Long,
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputCoinAmount: Double,
      balanceChanges: Map[String, cc.coinrules.BalanceChange],
      coinPrice: Double,
      memberTrafficCid: MemberTraffic.ContractId,
  ) =
    new BuyMemberTrafficResult(
      new Round(round),
      mkTransferSummary(
        inputAppRewardAmount,
        inputValidatorRewardAmount,
        // TODO (#9173): also test for sv rewards once the scan store supports them
        0.0,
        inputCoinAmount,
        balanceChanges,
        coinPrice,
      ),
      new java.math.BigDecimal(inputCoinAmount),
      memberTrafficCid,
      Optional.empty(),
    ).toValue

  private def coinRulesBuyMemberTrafficTransaction(
      provider: PartyId,
      memberId: Member,
      round: Long,
      extraTraffic: Long,
      ccSpent: Double,
  )(offset: String) = {
    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val coinRulesCid = nextCid()

    val memberTrafficCid = new MemberTraffic.ContractId(validContractId(round.toInt))

    val createdCoin = coin(provider, ccSpent, round, holdingFee)
    val coinCreateEvent = toCreatedEvent(createdCoin, signatories = Seq(provider, svcParty))
    val coinArchiveEvent = exercisedEvent(
      createdCoin.contractId.contractId,
      coinCodegen.Coin.TEMPLATE_ID,
      Some(cc.coin.Coin.TEMPLATE_ID),
      coinCodegen.Coin.CHOICE_Archive.name,
      consuming = true,
      new DamlRecord(),
      damlUnit.getInstance(),
    )

    mkExerciseTx(
      offset,
      exercisedEvent(
        coinRulesCid,
        cc.coinrules.CoinRules.TEMPLATE_ID,
        None,
        cc.coinrules.CoinRules.CHOICE_CoinRules_BuyMemberTraffic.name,
        consuming = false,
        new cc.coinrules.CoinRules_BuyMemberTraffic(
          java.util.List.of(),
          mkTransferContext(),
          provider.toProtoPrimitive,
          memberId.toProtoPrimitive,
          dummyDomain.toProtoPrimitive,
          extraTraffic,
        ).toValue,
        mkBuyMemberTrafficResult(
          round = round,
          inputAppRewardAmount = 0,
          inputValidatorRewardAmount = 0,
          inputCoinAmount = ccSpent,
          balanceChanges = Map.empty,
          coinPrice = 0.0005,
          memberTrafficCid = memberTrafficCid,
        ),
      ),
      Seq(
        // we don't care what the first event is for the store's purposes
        // also, the creation of the burnt coin should occur somewhere in the tx tree
        coinCreateEvent,
        coinArchiveEvent, // the third event has to be a coin burn
      ),
      dummyDomain,
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
        cc.coinrules.CoinRules.TEMPLATE_ID,
        None,
        cc.coinrules.CoinRules.CHOICE_CoinRules_Mint.name,
        consuming = false,
        new cc.coinrules.CoinRules_Mint(
          receiver.toProtoPrimitive,
          coinContract.payload.amount.initialAmount,
          new roundCodegen.OpenMiningRound.ContractId(openMiningRoundCid),
        ).toValue,
        new cc.coin.CoinCreateSummary[coinCodegen.Coin.ContractId](
          coinContract.contractId,
          new java.math.BigDecimal(coinPrice),
          new Round(round),
        )
          .toValue(_.toValue),
      ),
      Seq(toCreatedEvent(coinContract, signatories = Seq(receiver, svcParty))),
      dummyDomain,
    )
  }

  private def mkCoinExpire() =
    new coinCodegen.Coin_Expire(
      new roundCodegen.OpenMiningRound.ContractId(nextCid())
    ).toValue

  private def mkLockedCoinExpireCoin() =
    new coinCodegen.LockedCoin_ExpireCoin(
      new roundCodegen.OpenMiningRound.ContractId(nextCid())
    ).toValue

  private def mkCoinExpireSummary(
      owner: PartyId,
      round: Long,
      changeToInitialAmountAsOfRoundZero: Double,
      changeToHoldingFeesRate: Double,
  ) =
    new cc.coin.CoinExpireSummary(
      owner.toProtoPrimitive,
      new cc.types.Round(round),
      new java.math.BigDecimal(changeToInitialAmountAsOfRoundZero),
      new java.math.BigDecimal(changeToHoldingFeesRate),
    ).toValue

  private def importCrate(receiver: PartyId, n: Int) = {
    val template = new ImportCrate(
      svcParty.toProtoPrimitive,
      receiver.toProtoPrimitive,
      new IP_Coin(coinTemplate(n.toDouble, receiver)),
    )
    contract(
      ImportCrate.TEMPLATE_ID,
      new ImportCrate.ContractId(s"${receiver.toProtoPrimitive}::$n"),
      template,
    )
  }

  private def coinTemplate(amount: Double, owner: PartyId) = {
    new Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      expiringAmount(amount),
      Optional.empty(),
    )
  }

  private def expiringAmount(amount: Double) = new cc.fees.ExpiringAmount(
    numeric(amount),
    new cc.types.Round(0L),
    new cc.fees.RatePerRound(numeric(amount)),
  )

  private def svcRules(
      members: java.util.Map[String, svcrulesCodegen.MemberInfo] = Collections.emptyMap(),
      epoch: Long = 123,
  ) = {
    val templateId = svcrulesCodegen.SvcRules.TEMPLATE_ID
    val newDomainId = "new-domain-id"
    val template = new svcrulesCodegen.SvcRules(
      svcParty.toProtoPrimitive,
      epoch,
      members,
      Collections.emptyMap(),
      user1.toProtoPrimitive,
      new svcrulesCodegen.SvcRulesConfig(
        1,
        1,
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new globaldomainCodegen.DomainNodeConfigLimits(
          new cometbftCodegen.CometBftConfigLimits(1, 1, 1, 1, 1)
        ),
        1,
        1,
        new RelTime(1),
        new globaldomainCodegen.SvcGlobalDomainConfig(
          Collections.emptyMap(),
          newDomainId,
          newDomainId,
        ),
        Optional.empty(),
      ),
      Collections.emptyMap(),
      true,
    )
    contract(
      identifier = templateId,
      contractId = new svcrulesCodegen.SvcRules.ContractId(nextCid()),
      payload = template,
    )
  }

  private def cnsEntry(n: Int, name: String) = {
    val template = new CnsEntry(
      userParty(n).toProtoPrimitive,
      svcParty.toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
      Instant.now().plusSeconds(3600),
    )

    contract(
      CnsEntry.TEMPLATE_ID,
      new CnsEntry.ContractId(nextCid()),
      template,
    )
  }

  private def memberTraffic(member: Member, totalPurchased: Long) = {
    val template = new MemberTraffic(
      svcParty.toProtoPrimitive,
      member.toProtoPrimitive,
      dummyDomain.toProtoPrimitive,
      totalPurchased,
      1,
      numeric(1.0),
      numeric(1.0),
    )

    contract(
      MemberTraffic.TEMPLATE_ID,
      new MemberTraffic.ContractId(nextCid()),
      template,
    )
  }

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
}

class InMemoryScanStoreTest extends ScanStoreTest {
  override protected def mkStore(
      serviceUserPrimaryParty: PartyId,
      svcParty: PartyId,
  ): Future[ScanStore] = {
    val store = new InMemoryScanStore(
      serviceUserPrimaryParty = serviceUserPrimaryParty,
      svcParty = svcParty,
      loggerFactory,
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

  override protected def mkStore(
      serviceUserPrimaryParty: PartyId,
      svcParty: PartyId,
  ): Future[ScanStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.cantonCoin.all ++
          DarResources.cantonNameService.all ++
          DarResources.svcGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbScanStore(
      serviceUserPrimaryParty = serviceUserPrimaryParty,
      svcParty = svcParty,
      storage,
      // to allow aggregating from round zero without previous round aggregate
      ingestFromParticipantBegin = true,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      // required to instantiate a DbScanStore, returns none not to affect this test.
      _ =>
        new ScanAggregatesReader() {
          def readRoundAggregateFromSvc(round: Long)(implicit
              ec: ExecutionContext,
              traceContext: TraceContext,
          ): Future[Option[ScanAggregator.RoundAggregate]] = Future.successful(None)
        },
      domainMigrationId = 0,
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

  // TODO (#8152): move with the other tests. In-memory does not implement getTopValidatorLicenses
  "getTopValidatorLicenses" should {

    "return the top `limit` validator licenses by number of rounds collected" in {
      // total 1001
      val first = validatorLicense(
        userParty(9001),
        svcParty,
        Some(new FaucetState(new Round(0), new Round(1000), 0L)),
      )
      // total 1000
      val almostFirst = validatorLicense(
        userParty(2),
        svcParty,
        Some(new FaucetState(new Round(0), new Round(1000), 1L)),
      )
      // total 681
      val third = validatorLicense(
        userParty(2),
        svcParty,
        Some(new FaucetState(new Round(700), new Round(1000), 20L)),
      )
      // total 2
      val outOfLimit = validatorLicense(
        userParty(6),
        svcParty,
        Some(new FaucetState(new Round(999), new Round(1000), 0L)),
      )
      for {
        store <- mkStore()
        _ <- dummyDomain.create(outOfLimit)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(almostFirst)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(first)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(third)(store.multiDomainAcsStore)
        result <- store.getTopValidatorLicenses(PageLimit.tryCreate(3))
      } yield result shouldBe Seq(first, almostFirst, third)
    }

  }
}
