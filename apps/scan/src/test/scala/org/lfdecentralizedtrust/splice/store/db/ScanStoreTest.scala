package org.lfdecentralizedtrust.splice.store.db

import com.daml.ledger.javaapi.data.{DamlRecord, Unit as damlUnit}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext, SynchronizerAlias}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, Amulet_ExpireResult}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AmuletRules_BuyMemberTrafficResult,
  AmuletRules_MintResult,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsEntry
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer as decentralizedsynchronizerCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules,
  Reason,
  Vote,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.FaucetState
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  cometbft as cometbftCodegen,
  dsorules as dsorulesCodegen,
  round as roundCodegen,
}
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.history.*
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbScanStore,
  DbScanStoreMetrics,
  ScanAggregatesReader,
  ScanAggregator,
}
import org.lfdecentralizedtrust.splice.scan.store.*
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState.Assigned
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.util.SpliceUtil.damlDecimal
import org.lfdecentralizedtrust.splice.util.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Collections, Optional}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import scala.reflect.ClassTag
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart.{
  InitializeAcsAtLatestOffset,
  InitializeAcsAtOffset,
  UpdateHistoryInitAtLatestPrunedOffset,
  ResumeAtOffset,
}

abstract class ScanStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with StoreErrors
    with AmuletTransferUtil {

  "ScanStore" should {
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
          closedMiningRound(dsoParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(appRewards.zip(validatorRewards).zipWithIndex) {
            case ((appAmount, validatorAmount), round) =>
              dummyDomain.exercise(
                amuletRules(),
                Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
                Transfer.choice.name,
                mkAmuletRulesTransfer(user1, 1.0),
                mkTransferResultRecord(
                  round = round.toLong,
                  inputAppRewardAmount = appAmount,
                  inputValidatorRewardAmount = validatorAmount,
                  inputSvRewardAmount = 0,
                  inputAmuletAmount = 0,
                  balanceChanges = Map(),
                  amuletPrice = 0.0005,
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
          closedMiningRound(dsoParty, round = round.toLong)
        }

        for {
          store <- mkStore()
          _ <- MonadUtil.sequentialTraverse(appRewards.zipWithIndex) { case (amount, round) =>
            dummyDomain.exercise(
              amuletRules(),
              Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              Transfer.choice.name,
              mkAmuletRulesTransfer(user1, amount),
              mkTransferResultRecord(
                round = round.toLong,
                inputAppRewardAmount = amount,
                inputAmuletAmount = 0,
                inputValidatorRewardAmount = 0,
                inputSvRewardAmount = 0,
                balanceChanges = Map(),
                amuletPrice = 0.0005,
              ),
            )(store.multiDomainAcsStore)
          }
          _ <- MonadUtil.sequentialTraverse(validatorRewards.zipWithIndex) { case (amount, round) =>
            dummyDomain.exercise(
              amuletRules(),
              Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              Transfer.choice.name,
              mkAmuletRulesTransfer(user1, amount),
              mkTransferResultRecord(
                round = round.toLong,
                inputAppRewardAmount = 0,
                inputValidatorRewardAmount = amount,
                inputSvRewardAmount = 0,
                inputAmuletAmount = 0,
                balanceChanges = Map(),
                amuletPrice = 0.0005,
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

    "getAmuletConfigForRound" should {

      "return the amulet OpenMiningRoundTxLogEntry for the round" in {
        val wanted = openMiningRound(dsoParty, round = 2, amuletPrice = 2.0)
        val unwanted = openMiningRound(dsoParty, round = 3, amuletPrice = 3.0)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          val logEntry = store.getAmuletConfigForRound(round = 2).futureValue
          logEntry match {
            case omr: OpenMiningRoundTxLogEntry =>
              omr.round should be(wanted.payload.round.number)
            case x =>
              fail(s"Entry was not an OpenMiningRoundTxLogEntry but a $x")
          }
          numeric(logEntry.amuletCreateFee) should be(
            numeric(
              wanted.payload.transferConfigUsd.createFee.fee.divide(wanted.payload.amuletPrice)
            )
          )
        }
      }

    }

    "getRoundOfLatestData" should {

      "return the latest closed round" in {
        val closedBefore = (0 until 2).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }
        val closed = closedMiningRound(dsoParty, round = 2)
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
        val open = openMiningRound(dsoParty, round = 2, amuletPrice = 2.0)
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
      val closed = closedMiningRound(dsoParty, round = asOfEndOfRound)
      val closedBefore = (0 until asOfEndOfRound.toInt).map { round =>
        closedMiningRound(dsoParty, round = round.toLong)
      }
      for {
        store <- mkStore()
        _ <- MonadUtil.sequentialTraverse(closedBefore) { closed =>
          dummyDomain.create(closed)(store.multiDomainAcsStore)
        }
        _ <- dummyDomain.create(closed)(store.multiDomainAcsStore)
        _ <- MonadUtil.sequentialTraverse(providerRewardRounds) { case (provider, amount, round) =>
          dummyDomain.exercise(
            amuletRules(),
            Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
            Transfer.choice.name,
            mkAmuletRulesTransfer(provider, amount),
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
            mkTransferResultRecord(
              round = round,
              inputAppRewardAmount = amount,
              inputAmuletAmount = 0,
              inputValidatorRewardAmount = 0,
              inputSvRewardAmount = 0,
              balanceChanges = Map(),
              amuletPrice = 0.0005,
            ),
        )
      }
    }

    "getTopValidatorsByValidatorRewards" should {

      "return the top `limit` providers by app rewards" in {
        topProvidersTest(
          (store, round, limit) => store.getTopValidatorsByValidatorRewards(round, limit),
          (amount, round) =>
            mkTransferResultRecord(
              round = round,
              inputAppRewardAmount = 0,
              inputValidatorRewardAmount = amount,
              inputAmuletAmount = 0,
              inputSvRewardAmount = 0,
              balanceChanges = Map(),
              amuletPrice = 0.0005,
            ),
        )
      }
    }

    "getTopValidatorsByPurchasedTraffic" should {

      "return the top `limit` providers by purchased traffic" in {
        val asOfEndOfRound = 5L
        val trafficPurchaseTrees = Seq(
          // user 1
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 2,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(1),
            memberId = mkParticipantId("user-1"),
            round = 3,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          // user 2
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(2),
            memberId = mkParticipantId("user-2"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 2.0,
          )(_),
          // user 3
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(3),
            memberId = mkParticipantId("user-3"),
            round = 1,
            extraTraffic = 4,
            ccSpent = 3.0,
          )(_),
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(3),
            memberId = mkParticipantId("user-3"),
            round = 2,
            extraTraffic = 4,
            ccSpent = 3.0,
          )(_),
          // user 4
          amuletRulesBuyMemberTrafficTransaction(
            provider = userParty(4),
            memberId = mkParticipantId("user-4"),
            round = 1000, // excluded
            extraTraffic = 400000,
            ccSpent = 2222.0,
          )(_),
        )
        val closedBefore = (0 until asOfEndOfRound.toInt).map { round =>
          closedMiningRound(dsoParty, round = round.toLong)
        }
        val closed = closedMiningRound(dsoParty, round = asOfEndOfRound)
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
        val namespace = Namespace(Fingerprint.tryFromString(s"dummy"))
        val goodMember = ParticipantId(UniqueIdentifier.tryCreate("good", namespace))
        val badMember = MediatorId(UniqueIdentifier.tryCreate("bad", namespace))
        val goodContracts =
          (1 to 3).map(n => memberTraffic(goodMember, domainMigrationId, n.toLong))
        val badContracts =
          (4 to 6).map(n => memberTraffic(badMember, domainMigrationId, n.toLong)) ++
            (7 to 9).map(n => memberTraffic(goodMember, nextDomainMigrationId, n.toLong))
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

    "lookupAmuletRules" should {

      "find the latest amulet rules" in {
        val cr = amuletRules()
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupAmuletRules()
            .futureValue
            .map(_.contract) should be(Some(cr))
        }
      }

    }

    "lookupAnsRules" should {
      "find the latest ANS rules" in {
        val cr = ansRules()
        for {
          store <- mkStore()
          _ <- dummyDomain.create(cr)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupAnsRules()
            .futureValue
            .map(_.contract) should be(Some(cr))
        }
      }
    }

    "lookupDsoRules" should {
      "find the latest Dso rules" in {
        val sr = dsoRules(user1)
        for {
          store <- mkStore()
          _ <- dummyDomain.create(sr)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupDsoRules()
            .futureValue
            .map(_.contract) should be(Some(sr))
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
            .lookupFeaturedAppRight(userParty(1))
            .futureValue should be(expectedResult)
        }
      }
    }

    "lookupTransferPreapprovalByParty" should {
      "return the TransferPreapproval contract signed by the specified party if available" in {
        val wanted = transferPreapproval(userParty(1), providerParty(1), time(0), time(1))
        val unwanted = transferPreapproval(userParty(2), providerParty(1), time(0), time(1))
        val expectedResult = Some(ContractWithState(wanted, Assigned(dummyDomain)))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(wanted)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(unwanted)(store.multiDomainAcsStore)
        } yield {
          store.lookupTransferPreapprovalByParty(userParty(1)).futureValue should be(expectedResult)
          store.lookupTransferPreapprovalByParty(userParty(3)).futureValue should be(None)
        }
      }

      "return the latest created TransferPreapproval contract if there are multiple" in {
        val older =
          transferPreapproval(userParty(1), providerParty(1), validFrom = time(0), time(1))
        val newer =
          transferPreapproval(userParty(1), providerParty(2), validFrom = time(2), time(3))
        val expectedResult = Some(ContractWithState(newer, Assigned(dummyDomain)))
        for {
          store <- mkStore()
          _ <- dummyDomain.create(older)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(newer)(store.multiDomainAcsStore)
        } yield {
          store.lookupTransferPreapprovalByParty(userParty(1)).futureValue should be(expectedResult)
        }
      }
    }

    "lookupTransferCommandCounterByParty" should {
      "return the TransferCommandCounter for the specified party if available" in {
        val counter = transferCommandCounter(userParty(1), 0L)
        for {
          store <- mkStore()
          r <- store.lookupTransferCommandCounterByParty(userParty(1))
          _ = r shouldBe None
          _ <- dummyDomain.create(counter)(store.multiDomainAcsStore)
          r <- store.lookupTransferCommandCounterByParty(userParty(1))
          _ = r.map(_.contract) shouldBe Some(counter)
          r <- store.lookupTransferCommandCounterByParty(userParty(2))
          _ = r shouldBe None
        } yield succeed
      }
    }

    val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    val timeInThePast = now.minusSeconds(3600)

    "listEntries" should {
      "list entries with prefix" in {
        for {
          store <- mkStore()
          unwantedContract = ansEntry(1, "unwanted")
          wantedContract = ansEntry(2, "wanted")
          wantedContract2 = ansEntry(3, "wanted2")
          expiredContract = ansEntry(4, "wanted3", timeInThePast)
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiredContract)(store.multiDomainAcsStore)
          expectedResult = Seq(
            ContractWithState(wantedContract, Assigned(dummyDomain)),
            ContractWithState(wantedContract2, Assigned(dummyDomain)),
          )
        } yield {
          store
            .listEntries("wanted", CantonTimestamp.assertFromInstant(now))
            .futureValue should be(
            expectedResult
          )
          store.listEntries("dummy", CantonTimestamp.assertFromInstant(now)).futureValue should be(
            Seq.empty
          )
        }
      }
    }

    "lookupEntryByName" should {
      "return None for no entry" in {
        for {
          store <- mkStore()
          result <- store.lookupEntryByName("nope", CantonTimestamp.assertFromInstant(now))
        } yield result should be(None)
      }

      "return the entry with the exact name" in {
        for {
          store <- mkStore()
          unwantedContract = ansEntry(1, "unwanted")
          expiredContract = ansEntry(2, "wanted", timeInThePast)
          wantedContract = ansEntry(3, "wanted")
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiredContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupEntryByName(
              "wanted",
              CantonTimestamp.assertFromInstant(timeInThePast.minusSeconds(10)),
            )
            .futureValue should be(
            Some(ContractWithState(expiredContract, Assigned(dummyDomain)))
          )
          store
            .lookupEntryByName("wanted", CantonTimestamp.assertFromInstant(now))
            .futureValue should be(
            Some(ContractWithState(wantedContract, Assigned(dummyDomain)))
          )
        }
      }
    }

    "lookupEntryByParty" should {
      "return the first lexicographical entry of the user" in {
        for {
          store <- mkStore()
          unwantedContract = ansEntry(1, "unwanted")
          expiredContract = ansEntry(2, "expired", timeInThePast)
          bContract = ansEntry(2, "b")
          aContract = ansEntry(2, "a")
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(expiredContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(bContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(aContract)(store.multiDomainAcsStore)
        } yield {
          store
            .lookupEntryByParty(
              userParty(2),
              CantonTimestamp.assertFromInstant(timeInThePast.minusSeconds(10)),
            )
            .futureValue should be(Some(ContractWithState(aContract, Assigned(dummyDomain))))
          store
            .lookupEntryByParty(userParty(2), CantonTimestamp.assertFromInstant(now))
            .futureValue should be(Some(ContractWithState(aContract, Assigned(dummyDomain))))
        }
      }
    }

    "listTransactions" should {
      "return the most recent txs in pages" in {
        val limit = 10
        val nrTransfers = 20
        val round = 1L
        val now = java.time.Instant.EPOCH
        val zero = BigDecimal(0)
        val fakeOffset = "0"
        val txs: List[TransferTxLogEntry] = (1 to nrTransfers).map { i =>
          TransferTxLogEntry(
            offset = fakeOffset,
            eventId = s"$i",
            domainId = dummyDomain,
            date = Some(now),
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
                Some(zero),
                None,
              )
            ),
            balanceChanges = Seq(),
            receivers = Seq(ReceiverAmount(user2, BigDecimal(i), zero)),
            round = round,
            amuletPrice = BigDecimal(1.0),
          )
        }.toList
        def stripEventIdAndOffset(tx: TransferTxLogEntry) =
          tx.copy(eventId = "", offset = fakeOffset)
        val expectedFirstPage = txs.reverse.take(limit).toList
        val expectedSecondPage = txs.reverse.drop(limit).take(limit).toList

        def transferFromTransaction(
            store: ScanStore,
            amuletRulesContract: Contract[
              splice.amuletrules.AmuletRules.ContractId,
              splice.amuletrules.AmuletRules,
            ],
            tx: TransferTxLogEntry,
        ) = {
          val sender = tx.sender.getOrElse(throw txMissingField())
          val senderParty = sender.party
          val senderAmount = sender.inputAmuletAmount
          val receiverParty = tx.receivers(0).party
          val receiverAmount = tx.receivers(0).amount
          dummyDomain
            .exercise(
              contract = amuletRulesContract,
              interfaceId = Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
              choiceName = Transfer.choice.name,
              choiceArgument = mkAmuletRules_Transfer(
                mkTransferInputOutput(
                  senderParty,
                  senderParty,
                  List(mkInputAmulet()),
                  List(mkTransferOutput(receiverParty, receiverAmount)),
                )
              ),
              exerciseResult = mkTransferResultRecord(
                round = round,
                inputAppRewardAmount = sender.inputAppRewardAmount.toDouble,
                inputAmuletAmount = senderAmount.toDouble,
                inputValidatorRewardAmount = sender.inputValidatorRewardAmount.toDouble,
                inputSvRewardAmount = sender.inputSvRewardAmount.fold(0.0)(_.toDouble),
                balanceChanges = Map(),
                amuletPrice = tx.amuletPrice.toDouble,
              ),
            )(
              store.multiDomainAcsStore
            )
            .map(_ => ())
        }

        for {
          store <- mkStore()
          amuletRulesContract = amuletRules()
          _ <- txs.foldLeft(Future.successful(())) { (f, tx) =>
            f.flatMap { _ =>
              transferFromTransaction(
                store,
                amuletRulesContract,
                tx,
              )
            }
          }
        } yield {
          val firstPageDescending = store
            .listByType[TransferTxLogEntry](None, SortOrder.Descending, limit)
            .futureValue
            .toList

          firstPageDescending
            .map(stripEventIdAndOffset) should be(
            expectedFirstPage
              .map(stripEventIdAndOffset)
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
            .map(stripEventIdAndOffset) should be(
            expectedSecondPage
              .map(stripEventIdAndOffset)
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

    "votes" should {

      "listVoteRequestResults" should {

        "list all past VoteRequestResult" in {
          val store = mkStore().futureValue
          val voteRequestContracts = mkVoteRequests()
          assertListOfAllPastVoteRequestResults(voteRequestContracts, store)
        }
      }

      "listVoteRequestsByTrackingCid" should {

        "return all votes by their VoteRequest contract ids" in {
          val goodVotes = (1 to 3).map(n =>
            Seq(n, n + 3)
              .map(i =>
                new Vote(userParty(i).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
              )
          )
          val badVotes = (1 to 3).map(n =>
            Seq(n)
              .map(i =>
                new Vote(userParty(i).toProtoPrimitive, true, new Reason("", ""), Optional.empty())
              )
          )
          val goodVoteRequests =
            (1 to 3).map(n =>
              voteRequest(
                requester = userParty(n),
                votes = goodVotes(n - 1),
              )
            )
          val badVoteRequests =
            (4 to 6).map(n => voteRequest(requester = userParty(n), votes = badVotes(n - 4)))
          for {
            store <- mkStore()
            _ <- MonadUtil.sequentialTraverse(goodVoteRequests ++ badVoteRequests)(
              dummyDomain.create(_)(store.multiDomainAcsStore)
            )
            result <- store.listVoteRequestsByTrackingCid(goodVoteRequests.map(_.contractId))
            votes = result.flatMap(_.payload.votes.values().asScala)
          } yield {
            votes should contain theSameElementsAs (goodVotes.flatten)
          }
        }
      }
    }

    "lookupLatestTransferCommandEvent" should {
      def createTransferCommand(
          store: ScanStore,
          externalPartyRules: Contract[
            splice.externalpartyamuletrules.ExternalPartyAmuletRules.ContractId,
            splice.externalpartyamuletrules.ExternalPartyAmuletRules,
          ],
          transferCmd: Contract[
            splice.externalpartyamuletrules.TransferCommand.ContractId,
            splice.externalpartyamuletrules.TransferCommand,
          ],
      ) = {
        dummyDomain.exercise(
          externalPartyRules,
          interfaceId = Some(
            splice.externalpartyamuletrules.ExternalPartyAmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID
          ),
          ExternalPartyAmuletRules_CreateTransferCommand.choice.name,
          new splice.externalpartyamuletrules.ExternalPartyAmuletRules_CreateTransferCommand(
            transferCmd.payload.sender,
            transferCmd.payload.receiver,
            transferCmd.payload.delegate,
            transferCmd.payload.amount,
            transferCmd.payload.expiresAt,
            transferCmd.payload.nonce,
            transferCmd.payload.description,
            Optional.of(dsoParty.toProtoPrimitive),
          ).toValue,
          new splice.externalpartyamuletrules.ExternalPartyAmuletRules_CreateTransferCommandResult(
            transferCmd.contractId
          ).toValue,
          nextOffset(),
        )(
          store.multiDomainAcsStore
        )
      }

      "transitions from Created to Sent" in {
        for {
          store <- mkStore()
          transferCmd = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          counter = transferCommandCounter(
            userParty(1),
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()

          tx <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
              )
          )
          tx <- dummyDomain.exercise(
            transferCmd,
            interfaceId =
              Some(splice.externalpartyamuletrules.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID),
            TransferCommand_Send.choice.name,
            new splice.externalpartyamuletrules.TransferCommand_Send(
              mkPaymentTransferContext(rules.contractId),
              Seq.empty.asJava,
              None.toJava,
              counter.contractId,
            ).toValue,
            new splice.externalpartyamuletrules.TransferCommand_SendResult(
              new splice.externalpartyamuletrules.transfercommandresult.TransferCommandResultSuccess(
                mkTransferResult(
                  round = 0,
                  inputAppRewardAmount = 0,
                  inputAmuletAmount = 42.0,
                  inputValidatorRewardAmount = 0,
                  inputSvRewardAmount = 0,
                  balanceChanges = Map(
                    user1.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
                      BigDecimal(42.0).bigDecimal,
                      holdingFee.bigDecimal,
                    )
                  ),
                  amuletPrice = 0.0005,
                )
              ),
              transferCmd.payload.sender,
              transferCmd.payload.nonce,
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Sent(TransferCommandSent()),
              )
          )
        } yield succeed
      }

      "transitions from Created to Failed" in {
        for {
          store <- mkStore()
          transferCmd = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          counter = transferCommandCounter(
            userParty(1),
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()
          tx <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
              )
          )
          tx <- dummyDomain.exercise(
            transferCmd,
            interfaceId =
              Some(splice.externalpartyamuletrules.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID),
            TransferCommand_Send.choice.name,
            new splice.externalpartyamuletrules.TransferCommand_Send(
              mkPaymentTransferContext(rules.contractId),
              Seq.empty.asJava,
              None.toJava,
              counter.contractId,
            ).toValue,
            new splice.externalpartyamuletrules.TransferCommand_SendResult(
              new splice.externalpartyamuletrules.transfercommandresult.TransferCommandResultFailure(
                new splice.amuletrules.invalidtransferreason.ITR_Other("cool reason")
              ),
              transferCmd.payload.sender,
              transferCmd.payload.nonce,
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Failed(
                  TransferCommandFailed("ITR_Other(cool reason)")
                ),
              )
          )
        } yield succeed
      }
      "transitions from Created to Withdrawn" in {
        for {
          store <- mkStore()
          transferCmd = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          counter = transferCommandCounter(
            userParty(1),
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()
          tx <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
              )
          )
          tx <- dummyDomain.exercise(
            transferCmd,
            interfaceId =
              Some(splice.externalpartyamuletrules.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID),
            TransferCommand_Withdraw.choice.name,
            new splice.externalpartyamuletrules.TransferCommand_Withdraw(
            ).toValue,
            new splice.externalpartyamuletrules.TransferCommand_WithdrawResult(
              transferCmd.payload.sender,
              transferCmd.payload.nonce,
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Withdrawn(TransferCommandWithdrawn()),
              )
          )
        } yield succeed
      }

      "transitions from Created to Expired" in {
        for {
          store <- mkStore()
          transferCmd = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          counter = transferCommandCounter(
            userParty(1),
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()
          tx <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
              )
          )
          tx <- dummyDomain.exercise(
            transferCmd,
            interfaceId =
              Some(splice.externalpartyamuletrules.TransferCommand.TEMPLATE_ID_WITH_PACKAGE_ID),
            TransferCommand_Expire.choice.name,
            new splice.externalpartyamuletrules.TransferCommand_Expire(
              dsoParty.toProtoPrimitive
            ).toValue,
            new splice.externalpartyamuletrules.TransferCommand_ExpireResult(
              transferCmd.payload.sender,
              transferCmd.payload.nonce,
            ).toValue,
            nextOffset(),
          )(
            store.multiDomainAcsStore
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd.contractId ->
              TransferCommandTxLogEntry(
                EventId.prefixedFromUpdateIdAndNodeId(tx.getUpdateId, 0),
                PartyId.tryFromProtoPrimitive(transferCmd.payload.sender),
                transferCmd.payload.nonce,
                transferCmd.contractId.contractId,
                TransferCommandTxLogEntry.Status.Expired(TransferCommandExpired()),
              )
          )
        } yield succeed
      }

      "filters by sender and nonce" in {
        for {
          store <- mkStore()
          transferCmd1 = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          // different nonce, same sender
          transferCmd2 = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            1L,
          )
          // same nonce, different sender
          transferCmd3 = transferCommand(
            userParty(2),
            userParty(1),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          // same nonce, same sender, conflicts with transferCmd1
          transferCmd4 = transferCommand(
            userParty(1),
            userParty(2),
            userParty(3),
            42.0,
            Instant.EPOCH,
            0L,
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map.empty
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 1L, 10)
          _ = result shouldBe Map.empty
          result <- store.lookupLatestTransferCommandEvents(userParty(2), 0L, 10)
          _ = result shouldBe Map.empty
          rules = amuletRules()
          externalPartyRules = externalPartyAmuletRules()
          tx1 <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd1,
          )
          transferCmd1Status =
            TransferCommandTxLogEntry(
              EventId.prefixedFromUpdateIdAndNodeId(tx1.getUpdateId, 0),
              PartyId.tryFromProtoPrimitive(transferCmd1.payload.sender),
              transferCmd1.payload.nonce,
              transferCmd1.contractId.contractId,
              TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
            )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(transferCmd1.contractId -> transferCmd1Status)
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 1L, 10)
          _ = result shouldBe Map.empty
          result <- store.lookupLatestTransferCommandEvents(userParty(2), 0L, 10)
          _ = result shouldBe Map.empty
          tx2 <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd2,
          )
          tx3 <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd3,
          )
          tx4 <- createTransferCommand(
            store,
            externalPartyRules,
            transferCmd4,
          )
          transferCmd2Status = TransferCommandTxLogEntry(
            EventId.prefixedFromUpdateIdAndNodeId(tx2.getUpdateId, 0),
            PartyId.tryFromProtoPrimitive(transferCmd2.payload.sender),
            transferCmd2.payload.nonce,
            transferCmd2.contractId.contractId,
            TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
          )
          transferCmd3Status = TransferCommandTxLogEntry(
            EventId.prefixedFromUpdateIdAndNodeId(tx3.getUpdateId, 0),
            PartyId.tryFromProtoPrimitive(transferCmd3.payload.sender),
            transferCmd3.payload.nonce,
            transferCmd3.contractId.contractId,
            TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
          )
          transferCmd4Status = TransferCommandTxLogEntry(
            EventId.prefixedFromUpdateIdAndNodeId(tx4.getUpdateId, 0),
            PartyId.tryFromProtoPrimitive(transferCmd4.payload.sender),
            transferCmd4.payload.nonce,
            transferCmd4.contractId.contractId,
            TransferCommandTxLogEntry.Status.Created(TransferCommandCreated()),
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 10)
          _ = result shouldBe Map(
            transferCmd1.contractId -> transferCmd1Status,
            transferCmd4.contractId -> transferCmd4Status,
          )
          resultLimit <- store.lookupLatestTransferCommandEvents(userParty(1), 0L, 1)
          _ = resultLimit shouldBe Map(
            transferCmd1.contractId -> transferCmd1Status
          )
          result <- store.lookupLatestTransferCommandEvents(userParty(1), 1L, 10)
          _ = result shouldBe Map(transferCmd2.contractId -> transferCmd2Status)
          result <- store.lookupLatestTransferCommandEvents(userParty(2), 0L, 10)
          _ = result shouldBe Map(transferCmd3.contractId -> transferCmd3Status)
        } yield succeed
      }
    }

    "lookupContractByRecordTime" should {

      "find the DsoRules contract at a given time" in {
        val now = CantonTimestamp.now()
        val firstDsoRules = dsoRules(dsoParty, epoch = 1)
        val secondDsoRules = dsoRules(dsoParty, epoch = 2)
        val thirdDsoRules = dsoRules(dsoParty, epoch = 3)
        val recordTimeFirst = now.plusSeconds(1).toInstant
        val recordTimeSecond = now.plusSeconds(5).toInstant
        val recordTimeThird = now.plusSeconds(9).toInstant
        for {
          store <- mkStore()
          updateHistory <- mkUpdateHistory(domainMigrationId)
          _ <- updateHistory.ingestionSink.initialize()
          first <- dummyDomain.create(
            firstDsoRules,
            recordTime = recordTimeFirst,
          )(
            updateHistory
          )
          firstRecordTime = CantonTimestamp.fromInstant(first.getRecordTime).getOrElse(now)
          _ <- dummyDomain.create(
            secondDsoRules,
            recordTime = recordTimeSecond,
          )(updateHistory)
          _ <- dummyDomain.create(
            thirdDsoRules,
            recordTime = recordTimeThird,
          )(updateHistory)
          result <- store.lookupContractByRecordTime(
            DsoRules.COMPANION,
            updateHistory,
            firstRecordTime.plusSeconds(1),
          )
        } yield {
          result.value should not be firstDsoRules
          result.value shouldBe secondDsoRules
          result.value should not be thirdDsoRules
        }
      }

      "find the AmuletRules contract at a given time" in {
        val now = CantonTimestamp.now()
        val firstAmuletRules = amuletRules(10)
        val secondAmuletRules = amuletRules(20)
        val thirdAmuletRules = amuletRules(30)
        val recordTimeFirst = now.plusSeconds(1).toInstant
        val recordTimeSecond = now.plusSeconds(5).toInstant
        val recordTimeThird = now.plusSeconds(9).toInstant
        for {
          store <- mkStore()
          updateHistory <- mkUpdateHistory(domainMigrationId)
          _ <- updateHistory.ingestionSink.initialize()
          first <- dummyDomain.create(
            firstAmuletRules,
            recordTime = recordTimeFirst,
          )(
            updateHistory
          )
          firstRecordTime = CantonTimestamp.fromInstant(first.getRecordTime).getOrElse(now)
          _ <- dummyDomain.create(
            secondAmuletRules,
            recordTime = recordTimeSecond,
          )(
            updateHistory
          )
          _ <- dummyDomain.create(
            thirdAmuletRules,
            recordTime = recordTimeThird,
          )(updateHistory)
          result <- store.lookupContractByRecordTime(
            AmuletRules.COMPANION,
            updateHistory,
            firstRecordTime.plusSeconds(1),
          )
        } yield {
          result.value should not be firstAmuletRules
          result.value shouldBe secondAmuletRules
          result.value should not be thirdAmuletRules
        }
      }
    }
  }
  def mkVoteRequests(): Vector[Contract[VoteRequest.ContractId, VoteRequest]] = {
    val voteRequestContract1 = voteRequest(
      requester = userParty(1),
      votes = (1 to 4)
        .map(n =>
          new Vote(
            userParty(n).toProtoPrimitive,
            true,
            new Reason("", ""),
            Optional.empty(),
          )
        ),
    )
    val voteRequestContract2 = voteRequest(
      requester = userParty(2),
      votes = (1 to 4)
        .map(n =>
          new Vote(
            userParty(n).toProtoPrimitive,
            true,
            new Reason("", ""),
            Optional.empty(),
          )
        ),
    )
    Vector(voteRequestContract1, voteRequestContract2)
  }
  def assertListOfAllPastVoteRequestResults(
      voteRequestContracts: Vector[Contract[VoteRequest.ContractId, VoteRequest]],
      store: ScanStore,
  ) = {
    val voteRequestContract1 = voteRequestContracts(0)
    val voteRequestContract2 = voteRequestContracts(1)

    for {
      _ <- dummyDomain.create(voteRequestContract1)(store.multiDomainAcsStore)
      result1 = mkVoteRequestResult(
        voteRequestContract1
      )
      _ <- dummyDomain.exercise(
        contract = dsoRules(dsoParty),
        interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
        choiceName = DsoRulesCloseVoteRequest.choice.name,
        mkCloseVoteRequest(
          voteRequestContract1.contractId
        ),
        result1.toValue,
      )(
        store.multiDomainAcsStore
      )
      _ <- dummyDomain.create(voteRequestContract2)(store.multiDomainAcsStore)
      result2 = mkVoteRequestResult(
        voteRequestContract2,
        effectiveAt = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS),
      )
      _ <- dummyDomain.exercise(
        contract = dsoRules(dsoParty),
        interfaceId = Some(DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID),
        choiceName = DsoRulesCloseVoteRequest.choice.name,
        mkCloseVoteRequest(
          voteRequestContract2.contractId
        ),
        result2.toValue,
      )(
        store.multiDomainAcsStore
      )
    } yield {
      store
        .listVoteRequestResults(
          Some("AddSv"),
          Some(true),
          None,
          None,
          None,
          PageLimit.tryCreate(1),
        )
        .futureValue
        .toList
        .loneElement shouldBe result2
      store
        .listVoteRequestResults(
          Some("SRARC_AddSv"),
          Some(false),
          None,
          None,
          None,
          PageLimit.tryCreate(1),
        )
        .futureValue
        .toList
        .size shouldBe (0)
      store
        .listVoteRequestResults(
          None,
          None,
          None,
          None,
          None,
          PageLimit.tryCreate(1),
        )
        .futureValue
        .toList
        .size shouldBe (1)
      store
        .listVoteRequestResults(
          None,
          None,
          None,
          Some(Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600).toString),
          None,
          PageLimit.tryCreate(1),
        )
        .futureValue
        .toList
        .size shouldBe (0)
      store
        .listVoteRequestResults(
          None,
          None,
          None,
          Some(Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(3600).toString),
          None,
          PageLimit.tryCreate(1),
        )
        .futureValue
        .toList
        .size shouldBe (1)
    }
  }

  protected def mkStore(
      dsoParty: PartyId = dsoParty,
      acsStoreDescriptorUserVersion: Option[Long] = None,
      txLogStoreDescriptorUserVersion: Option[Long] = None,
  ): Future[ScanStore]

  protected def mkUpdateHistory(
      migrationId: Long
  ): Future[UpdateHistory]

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
}
trait AmuletTransferUtil { self: StoreTestBase =>
  def mkInputAmulet() = {
    new splice.amuletrules.transferinput.InputAmulet(
      new splice.amulet.Amulet.ContractId(nextCid())
    )
  }

  def mkTransferOutput(
      receiver: PartyId,
      amount: BigDecimal,
      receiverFeeRatio: BigDecimal = BigDecimal(0.0),
  ): splice.amuletrules.TransferOutput =
    new splice.amuletrules.TransferOutput(
      receiver.toProtoPrimitive,
      receiverFeeRatio.bigDecimal,
      amount.bigDecimal,
      Optional.empty(),
    )

  def mkTransfer(receiver: PartyId, amount: Double) =
    new splice.amuletrules.Transfer(
      receiver.toProtoPrimitive,
      receiver.toProtoPrimitive,
      java.util.List.of(mkInputAmulet()),
      java.util.List.of(mkTransferOutput(receiver, amount)),
      Optional.empty(),
    )

  def mkTransferContext() = new splice.amuletrules.TransferContext(
    new roundCodegen.OpenMiningRound.ContractId(nextCid()),
    java.util.Map.of(),
    java.util.Map.of(),
    Optional.empty(),
  )

  def mkPaymentTransferContext(amuletRules: splice.amuletrules.AmuletRules.ContractId) =
    new splice.amuletrules.PaymentTransferContext(
      amuletRules,
      mkTransferContext(),
    )

  def mkTransferInputOutput(
      sender: PartyId,
      provider: PartyId,
      transferInputs: List[splice.amuletrules.TransferInput],
      transferOutputs: List[splice.amuletrules.TransferOutput],
  ): splice.amuletrules.Transfer =
    new splice.amuletrules.Transfer(
      sender.toProtoPrimitive,
      provider.toProtoPrimitive,
      transferInputs.asJava,
      transferOutputs.asJava,
      Optional.empty(),
    )

  def mkAmuletRules_Transfer(transfer: splice.amuletrules.Transfer) =
    new splice.amuletrules.AmuletRules_Transfer(
      transfer,
      mkTransferContext(),
      Optional.of(dsoParty.toProtoPrimitive),
    ).toValue

  def mkAmuletRulesTransfer(receiver: PartyId, amount: Double) =
    new splice.amuletrules.AmuletRules_Transfer(
      mkTransfer(receiver, amount),
      mkTransferContext(),
      Optional.of(dsoParty.toProtoPrimitive),
    ).toValue

  def mkTransferSummary(
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputSvRewardAmount: Double,
      inputAmuletAmount: Double,
      balanceChanges: Map[String, splice.amuletrules.BalanceChange],
      amuletPrice: Double,
  ) = new splice.amuletrules.TransferSummary(
    damlDecimal(inputAppRewardAmount),
    damlDecimal(inputValidatorRewardAmount),
    damlDecimal(inputSvRewardAmount),
    damlDecimal(inputAmuletAmount),
    balanceChanges.asJava,
    damlDecimal(0.0),
    java.util.List.of(damlDecimal(0.0)),
    damlDecimal(0.0),
    damlDecimal(0.0),
    damlDecimal(amuletPrice),
    // the validator faucet amount is already included in the `inputValidatorRewardAmount`,
    // We'll set this here once we add support for showing faucet coupon rewards separately
    // from the usage-based validator rewards.
    // TODO(#968): track faucet coupon inputs separately
    java.util.Optional.empty(),
    java.util.Optional.empty(),
    java.util.Optional.empty(),
  )

  def mkTransferResult(
      round: Long,
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputSvRewardAmount: Double,
      inputAmuletAmount: Double,
      balanceChanges: Map[String, splice.amuletrules.BalanceChange],
      amuletPrice: Double,
  ) =
    new splice.amuletrules.TransferResult(
      new splice.types.Round(round),
      mkTransferSummary(
        inputAppRewardAmount,
        inputValidatorRewardAmount,
        inputSvRewardAmount,
        inputAmuletAmount,
        balanceChanges,
        amuletPrice,
      ),
      java.util.List.of(),
      Optional.empty(),
      Optional.empty(),
    )

  def mkTransferResultRecord(
      round: Long,
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputSvRewardAmount: Double,
      inputAmuletAmount: Double,
      balanceChanges: Map[String, splice.amuletrules.BalanceChange],
      amuletPrice: Double,
  ) = mkTransferResult(
    round,
    inputAppRewardAmount,
    inputValidatorRewardAmount,
    inputSvRewardAmount,
    inputAmuletAmount,
    balanceChanges,
    amuletPrice,
  ).toValue

  def mkAmuletRules_BuyMemberTrafficResult(
      round: Long,
      inputAppRewardAmount: Double,
      inputValidatorRewardAmount: Double,
      inputAmuletAmount: Double,
      balanceChanges: Map[String, splice.amuletrules.BalanceChange],
      amuletPrice: Double,
      memberTrafficCid: MemberTraffic.ContractId,
  ) =
    new AmuletRules_BuyMemberTrafficResult(
      new Round(round),
      mkTransferSummary(
        inputAppRewardAmount,
        inputValidatorRewardAmount,
        // TODO (DACH-NY/canton-network-node#9173): also test for sv rewards once the scan store supports them
        0.0,
        inputAmuletAmount,
        balanceChanges,
        amuletPrice,
      ),
      new java.math.BigDecimal(inputAmuletAmount),
      memberTrafficCid,
      Optional.empty(),
      Optional.empty(),
    ).toValue

  def amuletRulesBuyMemberTrafficTransaction(
      provider: PartyId,
      memberId: Member,
      round: Long,
      extraTraffic: Long,
      ccSpent: Double,
  )(offset: Long) = {
    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val amuletRulesCid = nextCid()

    val memberTrafficCid = new MemberTraffic.ContractId(validContractId(round.toInt))

    val createdAmulet = amulet(provider, ccSpent, round, holdingFee)
    val amuletCreateEvent = toCreatedEvent(createdAmulet, signatories = Seq(provider, dsoParty))
    val amuletArchiveEvent = exercisedEvent(
      createdAmulet.contractId.contractId,
      amuletCodegen.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID,
      Some(splice.amulet.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID),
      amuletCodegen.Amulet.CHOICE_Archive.name,
      consuming = true,
      new DamlRecord(),
      damlUnit.getInstance(),
    )

    mkExerciseTx(
      offset,
      exercisedEvent(
        amuletRulesCid,
        splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        splice.amuletrules.AmuletRules.CHOICE_AmuletRules_BuyMemberTraffic.name,
        consuming = false,
        new splice.amuletrules.AmuletRules_BuyMemberTraffic(
          java.util.List.of(),
          mkTransferContext(),
          provider.toProtoPrimitive,
          memberId.toProtoPrimitive,
          dummyDomain.toProtoPrimitive,
          domainMigrationId,
          extraTraffic,
          Optional.of(dsoParty.toProtoPrimitive),
        ).toValue,
        mkAmuletRules_BuyMemberTrafficResult(
          round = round,
          inputAppRewardAmount = 0,
          inputValidatorRewardAmount = 0,
          inputAmuletAmount = ccSpent,
          balanceChanges = Map.empty,
          amuletPrice = 0.0005,
          memberTrafficCid = memberTrafficCid,
        ),
      ),
      Seq(
        // we don't care what the first event is for the store's purposes
        // also, the creation of the burnt amulet should occur somewhere in the tx tree
        amuletCreateEvent,
        amuletArchiveEvent, // the third event has to be a amulet burn
      ),
      dummyDomain,
    )
  }

  /** A AmuletRules_Mint exercise event with one child Amulet create event */
  def mintTransaction(
      receiver: PartyId,
      amount: BigDecimal,
      round: Long,
      ratePerRound: BigDecimal,
      amuletPrice: Double = 1.0,
  )(
      offset: Long
  ) = {
    val amuletContract = amulet(receiver, amount, round, ratePerRound)

    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val amuletRulesCid = nextCid()
    val openMiningRoundCid = nextCid()

    mkExerciseTx(
      offset,
      exercisedEvent(
        amuletRulesCid,
        splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        splice.amuletrules.AmuletRules.CHOICE_AmuletRules_Mint.name,
        consuming = false,
        new splice.amuletrules.AmuletRules_Mint(
          receiver.toProtoPrimitive,
          amuletContract.payload.amount.initialAmount,
          new roundCodegen.OpenMiningRound.ContractId(openMiningRoundCid),
        ).toValue,
        new AmuletRules_MintResult(
          new splice.amulet.AmuletCreateSummary[amuletCodegen.Amulet.ContractId](
            amuletContract.contractId,
            new java.math.BigDecimal(amuletPrice),
            new Round(round),
          )
        ).toValue,
      ),
      Seq(toCreatedEvent(amuletContract, signatories = Seq(receiver, dsoParty))),
      dummyDomain,
    )
  }

  def mkAmuletExpire() =
    new amuletCodegen.Amulet_Expire(
      new roundCodegen.OpenMiningRound.ContractId(nextCid())
    ).toValue

  def mkLockedAmuletExpireAmulet() =
    new amuletCodegen.LockedAmulet_ExpireAmulet(
      new roundCodegen.OpenMiningRound.ContractId(nextCid())
    ).toValue

  def mkAmuletExpireResult(
      owner: PartyId,
      round: Long,
      changeToInitialAmountAsOfRoundZero: BigDecimal,
      changeToHoldingFeesRate: BigDecimal,
  ) =
    new Amulet_ExpireResult(
      new splice.amulet.AmuletExpireSummary(
        owner.toProtoPrimitive,
        new splice.types.Round(round),
        changeToInitialAmountAsOfRoundZero.bigDecimal,
        changeToHoldingFeesRate.bigDecimal,
      ),
      Optional.empty(),
    ).toValue

  def amuletTemplate(amount: Double, owner: PartyId) = {
    new Amulet(
      dsoParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      expiringAmount(amount),
    )
  }

  def expiringAmount(amount: Double) = new splice.fees.ExpiringAmount(
    numeric(amount),
    new splice.types.Round(0L),
    new splice.fees.RatePerRound(numeric(amount)),
  )

  def dsoRules(
      party: PartyId,
      svs: java.util.Map[String, dsorulesCodegen.SvInfo] = Collections.emptyMap(),
      epoch: Long = 123,
  ) = {
    val templateId = dsorulesCodegen.DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID
    val newSynchronizerId = "new-domain-id"
    val template = new dsorulesCodegen.DsoRules(
      dsoParty.toProtoPrimitive,
      epoch,
      svs,
      Collections.emptyMap(),
      party.toProtoPrimitive,
      new dsorulesCodegen.DsoRulesConfig(
        1,
        1,
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new decentralizedsynchronizerCodegen.SynchronizerNodeConfigLimits(
          new cometbftCodegen.CometBftConfigLimits(1, 1, 1, 1, 1)
        ),
        1,
        new decentralizedsynchronizerCodegen.DsoDecentralizedSynchronizerConfig(
          Collections.emptyMap(),
          newSynchronizerId,
          newSynchronizerId,
        ),
        Optional.empty(),
        Optional.empty(), // voteCooldownTime
      ),
      Collections.emptyMap(),
      true,
    )
    contract(
      identifier = templateId,
      contractId = new dsorulesCodegen.DsoRules.ContractId(nextCid()),
      payload = template,
    )
  }

  def ansEntry(
      n: Int,
      name: String,
      expiresAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
  ) = {
    val template = new AnsEntry(
      userParty(n).toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      name,
      s"https://example.com/$name",
      s"Test with $name",
      expiresAt,
    )

    contract(
      AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID,
      new AnsEntry.ContractId(nextCid()),
      template,
    )
  }

  def memberTraffic(member: Member, domainMigrationId: Long, totalPurchased: Long) = {
    val template = new MemberTraffic(
      dsoParty.toProtoPrimitive,
      member.toProtoPrimitive,
      dummyDomain.toProtoPrimitive,
      domainMigrationId,
      totalPurchased,
      1,
      numeric(1.0),
      numeric(1.0),
    )

    contract(
      MemberTraffic.TEMPLATE_ID_WITH_PACKAGE_ID,
      new MemberTraffic.ContractId(nextCid()),
      template,
    )
  }

  lazy val domain = dummyDomain.toProtoPrimitive
}

class DbScanStoreTest
    extends ScanStoreTest
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(
      dsoParty: PartyId,
      acsStoreDescriptorUserVersion: Option[Long] = None,
      txLogStoreDescriptorUserVersion: Option[Long] = None,
  ): Future[ScanStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.amuletNameService.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbScanStore(
      key = ScanStore.Key(dsoParty),
      storage,
      // to allow aggregating from round zero without previous round aggregate
      isFirstSv = true,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      // required to instantiate a DbScanStore, returns none not to affect this test.
      _ =>
        new ScanAggregatesReader() {
          def readRoundAggregateFromDso(round: Long)(implicit
              ec: ExecutionContext,
              traceContext: TraceContext,
          ): Future[Option[ScanAggregator.RoundAggregate]] = Future.successful(None)
          def close(): Unit = ()
        },
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      participantId = mkParticipantId("ScanStoreTest"),
      IngestionConfig(),
      new DbScanStoreMetrics(new NoOpMetricsFactory(), loggerFactory, timeouts),
      initialRound = 0,
      acsStoreDescriptorUserVersion,
      txLogStoreDescriptorUserVersion,
    )(parallelExecutionContext, implicitly, implicitly)

    for {
      initializeResult <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- initializeResult match {
        case ResumeAtOffset(_) | UpdateHistoryInitAtLatestPrunedOffset => Future.unit
        case InitializeAcsAtLatestOffset =>
          store.multiDomainAcsStore.testIngestionSink.ingestAcs(
            nextOffset(),
            Seq.empty,
            Seq.empty,
            Seq.empty,
          )
        case InitializeAcsAtOffset(_) =>
          store.multiDomainAcsStore.testIngestionSink.ingestAcs(
            nextOffset(),
            Seq.empty,
            Seq.empty,
            Seq.empty,
          )
      }
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(SynchronizerAlias.tryCreate(domain) -> dummyDomain)
      )
    } yield store
  }

  override def mkUpdateHistory(
      migrationId: Long
  ): Future[UpdateHistory] = {
    val updateHistory = new UpdateHistory(
      storage.underlying, // not under test
      new DomainMigrationInfo(migrationId, None),
      "update_history_scan_store_test",
      mkParticipantId("whatever"),
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = true,
      HistoryMetrics(NoOpMetricsFactory, migrationId),
    )
    updateHistory.ingestionSink.initialize().map(_ => updateHistory)
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()

  "getTopValidatorLicenses" should {

    "return the top `limit` validator licenses by number of rounds collected" in {
      // total 1001
      val first = validatorLicense(
        userParty(9001),
        dsoParty,
        Some(new FaucetState(new Round(0), new Round(1000), 0L)),
      )
      // total 1000
      val almostFirst = validatorLicense(
        userParty(2),
        dsoParty,
        Some(new FaucetState(new Round(0), new Round(1000), 1L)),
      )
      // total 681
      val third = validatorLicense(
        userParty(2),
        dsoParty,
        Some(new FaucetState(new Round(700), new Round(1000), 20L)),
      )
      // total 2
      val outOfLimit = validatorLicense(
        userParty(6),
        dsoParty,
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

  "getValidatorFaucetsByValidator" should {

    "return the validator license of a specified validator" in {
      val alice = userParty(443)
      val aliceValidatorLicense = validatorLicense(
        alice,
        dsoParty,
        Some(new FaucetState(new Round(0), new Round(1000), 0L)),
      )
      val bob = userParty(444)
      val bobValidatorLicense = validatorLicense(
        bob,
        dsoParty,
        Some(new FaucetState(new Round(1), new Round(1001), 1L)),
      )
      val charles = userParty(445)
      val charlesValidatorLicense = validatorLicense(
        charles,
        dsoParty,
        Some(new FaucetState(new Round(3), new Round(1002), 2L)),
      )
      for {
        store <- mkStore()
        _ <- dummyDomain.create(bobValidatorLicense)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(aliceValidatorLicense)(store.multiDomainAcsStore)
        _ <- dummyDomain.create(charlesValidatorLicense)(store.multiDomainAcsStore)
        result <- store.getValidatorLicenseByValidator(
          Vector(alice, bob)
        )
      } yield {
        result should contain(aliceValidatorLicense)
        result should contain(bobValidatorLicense)
        result should not contain charlesValidatorLicense
      }
    }
  }
  "Changing the acsStoreDescriptorUserVersion" should {
    val alice = userParty(443)
    val aliceValidatorLicense = validatorLicense(
      alice,
      dsoParty,
      Some(new FaucetState(new Round(0), new Round(1000), 0L)),
    )

    "force re-ingestion of acs" in {
      for {
        // ingestion in these store tests is simulated by directly interacting with the ingestion sink (dummyDomain.create)
        // create store, ingest an update with aliceValidatorLicense
        store <- mkStore()
        _ <- dummyDomain.create(aliceValidatorLicense)(store.multiDomainAcsStore)
        result <- store.getValidatorLicenseByValidator(
          Vector(alice)
        )
        // create store again but now with new storeDescriptor userVersion, ingest an update with aliceValidatorLicense again
        storeReingest <- mkStore(dsoParty = dsoParty, acsStoreDescriptorUserVersion = Some(1L))
        // Below 'dummyDomain.create' would fail on the same storeId for aliceValidatorLicense without a new user version.
        // there is a unique constraint on (store_id, migration_id, contract_id) in the acs table (acs_store_template_sid_mid_cid) that would be violated.
        // Successfully creating the license again here proves that the store has switched to a new store descriptor.
        _ <- dummyDomain.create(aliceValidatorLicense)(storeReingest.multiDomainAcsStore)
        resultAfter <- storeReingest.getValidatorLicenseByValidator(
          Vector(alice)
        )
      } yield {
        result should contain(aliceValidatorLicense)
        resultAfter should contain(aliceValidatorLicense)
      }
    }
  }

  "Changing the txLogStoreDescriptorUserVersion" should {
    "force re-ingestion of txLog" in {
      val activeVoteRequest = voteRequest(
        requester = userParty(4),
        votes = (1 to 4)
          .map(n =>
            new Vote(
              userParty(n).toProtoPrimitive,
              true,
              new Reason("", ""),
              Optional.empty(),
            )
          ),
      )
      for {
        store <- mkStore()
        voteRequestContracts = mkVoteRequests() :+ activeVoteRequest
        _ <- assertListOfAllPastVoteRequestResults(voteRequestContracts, store)
        _ <- dummyDomain.create(activeVoteRequest)(store.multiDomainAcsStore)
        // create the store with a new txLogStoreDescriptorUserVersion and
        // check that it has acs entries but no txLog entries of the previous store
        // this proves that the store descriptor has changed and a new storeId is used.
        storeReingest <- mkStore(
          dsoParty = dsoParty,
          txLogStoreDescriptorUserVersion = Some(1L),
        )
      } yield {
        // new store should not have txLog entry from previous store
        // because ingestion in these store tests is simulated by directly interacting with the ingestion sink
        storeReingest
          .listVoteRequestResults(
            Some("AddSv"),
            Some(true),
            None,
            None,
            None,
            PageLimit.tryCreate(1),
          )
          .futureValue
          .toList should have size 0
        // should have the active acs entry
        storeReingest.listVoteRequests().futureValue.toList should contain(activeVoteRequest)
      }
    }
  }
}
