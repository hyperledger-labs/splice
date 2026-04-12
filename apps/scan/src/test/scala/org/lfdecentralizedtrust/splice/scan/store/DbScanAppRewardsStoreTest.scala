package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.rewards.RewardIssuanceParams
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbAppActivityRecordStore,
  DbScanAppRewardsStore,
}
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore.*
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, StoreTestBase, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.google.protobuf.ByteString
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.Future

class DbScanAppRewardsStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  import DbScanAppRewardsStoreTest.{Activity, DecimalSamples, IssuanceRate, Threshold, roundNumber}

  private val migrationId = 0L

  "DbScanAppRewardsStore" should {

    // -- Test 1: Insert and read back a single row per table ----------------

    "insert and read back app_activity_party_totals" in {
      for {
        (store, historyId) <- newStore()
        row = AppActivityPartyTotalT(
          historyId = historyId,
          roundNumber = roundNumber,
          totalAppActivityWeight = 123456L,
          appProviderPartySeqNum = 0,
          appProviderParty = "alice::provider",
          numActivityRecords = 3L,
        )
        _ <- store.insertAppActivityPartyTotals(Seq(row))
        loaded <- store.getAppActivityPartyTotalsByRound(roundNumber)
      } yield {
        loaded should have size 1
        loaded.head shouldBe row
      }
    }

    "insert and read back app_activity_round_totals" in {
      for {
        (store, historyId) <- newStore()
        row = AppActivityRoundTotalT(
          historyId = historyId,
          roundNumber = roundNumber,
          totalRoundAppActivityWeight = 999999L,
          activeAppProviderPartiesCount = 5L,
          activityRecordsCount = 42L,
        )
        _ <- store.insertAppActivityRoundTotals(Seq(row))
        loaded <- store.getAppActivityRoundTotalByRound(roundNumber)
      } yield {
        loaded.value shouldBe row
      }
    }

    "insert and read back app_reward_party_totals" in {
      for {
        (store, historyId) <- newStore()
        activityRow = AppActivityPartyTotalT(
          historyId = historyId,
          roundNumber = roundNumber,
          totalAppActivityWeight = 500L,
          appProviderPartySeqNum = 0,
          appProviderParty = "bob::provider",
          numActivityRecords = 1L,
        )
        _ <- store.insertAppActivityPartyTotals(Seq(activityRow))
        rewardRow = AppRewardPartyTotalT(
          historyId = historyId,
          roundNumber = roundNumber,
          appProviderPartySeqNum = 0,
          totalAppRewardAmount = BigDecimal("12345678901234567890.1234567891"),
        )
        _ <- store.insertAppRewardPartyTotals(Seq(rewardRow))
        loaded <- store.getAppRewardPartyTotalsByRound(roundNumber)
      } yield {
        loaded should have size 1
        loaded.head shouldBe rewardRow
        // Verify decimal precision round-trips correctly
        loaded.head.totalAppRewardAmount shouldBe BigDecimal("12345678901234567890.1234567891")
      }
    }

    "decimal(38,10) boundary values round-trip correctly" in {
      import DecimalSamples.*
      val testValues = Seq(
        Zero,
        One,
        NegOne,
        MinPositiveFractional,
        MinNegativeFractional,
        FractionalOnly,
        TypicalPositive,
        TypicalNegative,
        LargePositive,
        MaxDecimal,
        MinDecimal,
      )
      for {
        (store, historyId) <- newStore()
        activityRows = testValues.zipWithIndex.map { case (_, i) =>
          AppActivityPartyTotalT(
            historyId = historyId,
            roundNumber = roundNumber,
            totalAppActivityWeight = 1L,
            appProviderPartySeqNum = i,
            appProviderParty = s"party-$i::provider",
            numActivityRecords = 1L,
          )
        }
        _ <- store.insertAppActivityPartyTotals(activityRows)
        rewardRows = testValues.zipWithIndex.map { case (amount, i) =>
          AppRewardPartyTotalT(
            historyId = historyId,
            roundNumber = roundNumber,
            appProviderPartySeqNum = i,
            totalAppRewardAmount = amount,
          )
        }
        _ <- store.insertAppRewardPartyTotals(rewardRows)
        loaded <- store.getAppRewardPartyTotalsByRound(roundNumber)
      } yield {
        loaded should have size testValues.size.toLong
        loaded.map(_.totalAppRewardAmount) shouldBe testValues
      }
    }

    "insert and read back app_reward_round_totals" in {
      for {
        (store, historyId) <- newStore()
        row = AppRewardRoundTotalT(
          historyId = historyId,
          roundNumber = roundNumber,
          totalAppRewardMintingAllowance = BigDecimal("100.5000000000"),
          totalAppRewardThresholded = BigDecimal("10.2500000000"),
          totalAppRewardUnclaimed = BigDecimal("5.0000000000"),
          rewardedAppProviderPartiesCount = 3L,
        )
        _ <- store.insertAppRewardRoundTotals(Seq(row))
        loaded <- store.getAppRewardRoundTotalByRound(roundNumber)
      } yield {
        loaded.value shouldBe row
      }
    }

    "insert and read back app_reward_batch_hashes" in {
      for {
        (store, historyId) <- newStore()
        hash = ByteString.copyFrom(
          Array[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        )
        row = AppRewardBatchHashT(
          historyId = historyId,
          roundNumber = roundNumber,
          batchLevel = 0,
          partySeqNumBeginIncl = 0,
          partySeqNumEndExcl = 10,
          batchHash = hash,
        )
        _ <- store.insertAppRewardBatchHashes(Seq(row))
        loaded <- store.getAppRewardBatchHashesByRound(roundNumber)
      } yield {
        loaded should have size 1
        loaded.head shouldBe row
      }
    }

    "insert and read back app_reward_root_hashes" in {
      for {
        (store, historyId) <- newStore()
        hash = ByteString.copyFrom(Array[Byte](0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte))
        row = AppRewardRootHashT(
          historyId = historyId,
          roundNumber = roundNumber,
          rootHash = hash,
        )
        _ <- store.insertAppRewardRootHashes(Seq(row))
        loaded <- store.getAppRewardRootHashByRound(roundNumber)
      } yield {
        loaded.value shouldBe row
      }
    }

    // -- Test 2: Batch inserts ----------------------------------------------

    "batch insert multiple app_activity_party_totals and spot-check" in {
      for {
        (store, historyId) <- newStore()
        rows = (0 until 10).map { i =>
          AppActivityPartyTotalT(
            historyId = historyId,
            roundNumber = roundNumber,
            totalAppActivityWeight = (i + 1) * 100L,
            appProviderPartySeqNum = i,
            appProviderParty = s"party-$i::provider",
            numActivityRecords = 1L,
          )
        }
        _ <- store.insertAppActivityPartyTotals(rows)
        loaded <- store.getAppActivityPartyTotalsByRound(roundNumber)
      } yield {
        loaded should have size 10
        loaded.head shouldBe rows(0)
        loaded(4) shouldBe rows(4)
        loaded.last shouldBe rows(9)
      }
    }

    "batch insert multiple app_reward_batch_hashes and spot-check" in {
      for {
        (store, historyId) <- newStore()
        rows = (0 until 5).map { i =>
          AppRewardBatchHashT(
            historyId = historyId,
            roundNumber = roundNumber,
            batchLevel = 0,
            partySeqNumBeginIncl = i * 10,
            partySeqNumEndExcl = (i + 1) * 10,
            batchHash = ByteString.copyFrom(Array.fill(32)((i + 1).toByte)),
          )
        }
        _ <- store.insertAppRewardBatchHashes(rows)
        loaded <- store.getAppRewardBatchHashesByRound(roundNumber)
      } yield {
        loaded should have size 5
        loaded.head shouldBe rows(0)
        loaded(2) shouldBe rows(2)
        loaded.last shouldBe rows(4)
      }
    }

    // -- Test 5: Duplicate key handling (reject) ----------------------------

    "reject duplicate app_activity_party_totals on PK conflict" in {
      for {
        (store, historyId) <- newStore()
        row = AppActivityPartyTotalT(
          historyId = historyId,
          roundNumber = roundNumber,
          totalAppActivityWeight = 100L,
          appProviderPartySeqNum = 0,
          appProviderParty = "dup::provider",
          numActivityRecords = 1L,
        )
        _ <- store.insertAppActivityPartyTotals(Seq(row))
        duplicate = row.copy(totalAppActivityWeight = 200L)
        result <- store.insertAppActivityPartyTotals(Seq(duplicate)).failed
      } yield {
        result.getMessage should (include("unique constraint") or include("duplicate key"))
      }
    }

    "reject duplicate app_activity_round_totals on PK conflict" in {
      for {
        (store, historyId) <- newStore()
        row = AppActivityRoundTotalT(
          historyId = historyId,
          roundNumber = roundNumber,
          totalRoundAppActivityWeight = 1000L,
          activeAppProviderPartiesCount = 2L,
          activityRecordsCount = 10L,
        )
        _ <- store.insertAppActivityRoundTotals(Seq(row))
        duplicate = row.copy(totalRoundAppActivityWeight = 2000L)
        result <- store.insertAppActivityRoundTotals(Seq(duplicate)).failed
      } yield {
        result.getMessage should (include("unique constraint") or include("duplicate key"))
      }
    }

    "reject duplicate app_reward_root_hashes on PK conflict" in {
      for {
        (store, historyId) <- newStore()
        row = AppRewardRootHashT(
          historyId = historyId,
          roundNumber = roundNumber,
          rootHash = ByteString.copyFrom(Array[Byte](1, 2, 3, 4)),
        )
        _ <- store.insertAppRewardRootHashes(Seq(row))
        duplicate = AppRewardRootHashT(
          historyId = historyId,
          roundNumber = roundNumber,
          rootHash = ByteString.copyFrom(Array[Byte](5, 6, 7, 8)),
        )
        result <- store.insertAppRewardRootHashes(Seq(duplicate)).failed
      } yield {
        result.getMessage should (include("unique constraint") or include("duplicate key"))
      }
    }

    // -- Aggregation tests ---------------------------------------------------

    "aggregateActivityTotals — single round, single party" in {
      for {
        (store, historyId) <- newStore()
        _ <- insertSentinelRecords(historyId, roundNumber)
        _ <- insertActivityRecord(historyId, roundNumber, Seq("alice::provider"), Seq(500L))
        _ <- store.aggregateActivityTotals(roundNumber)
        partyTotals <- store.getAppActivityPartyTotalsByRound(roundNumber)
        roundTotal <- store.getAppActivityRoundTotalByRound(roundNumber)
      } yield {
        partyTotals should have size 1
        partyTotals.head.appProviderParty shouldBe "alice::provider"
        partyTotals.head.totalAppActivityWeight shouldBe 500L
        partyTotals.head.appProviderPartySeqNum shouldBe 0
        partyTotals.head.numActivityRecords shouldBe 1L

        roundTotal.value.totalRoundAppActivityWeight shouldBe 500L
        roundTotal.value.activeAppProviderPartiesCount shouldBe 1L
        roundTotal.value.activityRecordsCount shouldBe 1L
      }
    }

    "aggregateActivityTotals — multiple parties with correct GROUP BY and seq_nums" in {
      for {
        (store, historyId) <- newStore()
        _ <- insertSentinelRecords(historyId, roundNumber)
        // Two records in the same round with overlapping parties
        _ <- insertActivityRecord(
          historyId,
          roundNumber,
          Seq("bob::provider", "alice::provider"),
          Seq(300L, 200L),
        )
        _ <- insertActivityRecord(
          historyId,
          roundNumber,
          Seq("alice::provider", "charlie::provider"),
          Seq(100L, 400L),
        )
        _ <- store.aggregateActivityTotals(roundNumber)
        partyTotals <- store.getAppActivityPartyTotalsByRound(roundNumber)
        roundTotal <- store.getAppActivityRoundTotalByRound(roundNumber)
      } yield {
        partyTotals should have size 3
        // Sorted alphabetically: alice, bob, charlie → seq_nums 0, 1, 2
        partyTotals(0).appProviderParty shouldBe "alice::provider"
        partyTotals(0).totalAppActivityWeight shouldBe 300L // 200 + 100
        partyTotals(0).appProviderPartySeqNum shouldBe 0
        partyTotals(0).numActivityRecords shouldBe 2L // appears in both records

        partyTotals(1).appProviderParty shouldBe "bob::provider"
        partyTotals(1).totalAppActivityWeight shouldBe 300L
        partyTotals(1).appProviderPartySeqNum shouldBe 1
        partyTotals(1).numActivityRecords shouldBe 1L

        partyTotals(2).appProviderParty shouldBe "charlie::provider"
        partyTotals(2).totalAppActivityWeight shouldBe 400L
        partyTotals(2).appProviderPartySeqNum shouldBe 2
        partyTotals(2).numActivityRecords shouldBe 1L

        roundTotal.value.totalRoundAppActivityWeight shouldBe 1000L // 300+300+400
        roundTotal.value.activeAppProviderPartiesCount shouldBe 3L
        roundTotal.value.activityRecordsCount shouldBe 4L // sum of per-party counts: alice=2 + bob=1 + charlie=1
      }
    }

    "aggregateActivityTotals — empty round produces zero totals" in {
      for {
        (store, historyId) <- newStore()
        _ <- insertSentinelRecords(historyId, roundNumber)
        // No activity records for this round itself, but sentinels prove completeness
        _ <- store.aggregateActivityTotals(roundNumber)
        partyTotals <- store.getAppActivityPartyTotalsByRound(roundNumber)
        roundTotal <- store.getAppActivityRoundTotalByRound(roundNumber)
      } yield {
        partyTotals shouldBe empty
        roundTotal.value.totalRoundAppActivityWeight shouldBe 0L
        roundTotal.value.activeAppProviderPartiesCount shouldBe 0L
        roundTotal.value.activityRecordsCount shouldBe 0L
      }
    }

    "aggregateActivityTotals — only aggregates records from own history_id" in {
      for {
        (store1, historyId1) <- newStore()
        (_, historyId2) <- newStore()
        _ <- insertSentinelRecords(historyId1, roundNumber)
        // Insert activity records for the same round under both historyIds
        _ <- insertActivityRecord(historyId1, roundNumber, Seq("alice::provider"), Seq(100L))
        _ <- insertActivityRecord(historyId2, roundNumber, Seq("alice::provider"), Seq(900L))
        // Aggregate with store1 — should only see historyId1's data
        _ <- store1.aggregateActivityTotals(roundNumber)
        partyTotals <- store1.getAppActivityPartyTotalsByRound(roundNumber)
        roundTotal <- store1.getAppActivityRoundTotalByRound(roundNumber)
      } yield {
        partyTotals should have size 1
        partyTotals.head.appProviderParty shouldBe "alice::provider"
        partyTotals.head.totalAppActivityWeight shouldBe 100L
        partyTotals.head.numActivityRecords shouldBe 1L
        roundTotal.value.totalRoundAppActivityWeight shouldBe 100L
        roundTotal.value.activityRecordsCount shouldBe 1L
      }
    }

    "aggregateActivityTotals — re-run for same round raises error" in {
      for {
        (store, historyId) <- newStore()
        _ <- insertSentinelRecords(historyId, roundNumber)
        _ <- insertActivityRecord(historyId, roundNumber, Seq("alice::provider"), Seq(500L))
        _ <- store.aggregateActivityTotals(roundNumber)
        result <- store.aggregateActivityTotals(roundNumber).failed
      } yield {
        result.getMessage should (include("unique constraint") or include("duplicate key"))
      }
    }

    "aggregateActivityTotals — rejects round with incomplete activity (missing previous)" in {
      for {
        (store, historyId) <- newStore()
        // Only insert next-round sentinel, not previous
        _ <- insertActivityRecord(historyId, roundNumber + 1, Seq("sentinel::provider"), Seq(1L))
        _ <- insertActivityRecord(historyId, roundNumber, Seq("alice::provider"), Seq(500L))
        result <- store.aggregateActivityTotals(roundNumber).failed
      } yield {
        result.getMessage should include("Incomplete app activity")
        result.getMessage should include(s"round ${roundNumber - 1} exists=false")
      }
    }

    "aggregateActivityTotals — rejects round with incomplete activity (missing next)" in {
      for {
        (store, historyId) <- newStore()
        // Only insert previous-round sentinel, not next
        _ <- insertActivityRecord(historyId, roundNumber - 1, Seq("sentinel::provider"), Seq(1L))
        _ <- insertActivityRecord(historyId, roundNumber, Seq("alice::provider"), Seq(500L))
        result <- store.aggregateActivityTotals(roundNumber).failed
      } yield {
        result.getMessage should include("Incomplete app activity")
        result.getMessage should include(s"round ${roundNumber + 1} exists=false")
      }
    }

    // -- lookupLatestRoundWithRewardComputation ------

    "lookupLatestRoundWithRewardComputation returns None when no root hashes" in {
      for {
        (store, historyId) <- newStore()
        result <- store.lookupLatestRoundWithRewardComputation()
      } yield {
        result shouldBe None
      }
    }

    "lookupLatestRoundWithRewardComputation returns latest round with root hash" in {
      for {
        (store, historyId) <- newStore()
        _ <- store.insertAppRewardRootHashes(
          Seq(
            AppRewardRootHashT(
              historyId = historyId,
              roundNumber = 10L,
              rootHash = ByteString.copyFrom(Array[Byte](1, 2, 3, 4)),
            ),
            AppRewardRootHashT(
              historyId = historyId,
              roundNumber = 20L,
              rootHash = ByteString.copyFrom(Array[Byte](5, 6, 7, 8)),
            ),
          )
        )
        result <- store.lookupLatestRoundWithRewardComputation()
      } yield {
        result.value shouldBe 20L
      }
    }

    "lookupLatestRoundWithRewardComputation returns single round" in {
      for {
        (store, historyId) <- newStore()
        _ <- store.insertAppRewardRootHashes(
          Seq(
            AppRewardRootHashT(
              historyId = historyId,
              roundNumber = 5L,
              rootHash = ByteString.copyFrom(Array[Byte](1, 2, 3, 4)),
            )
          )
        )
        result <- store.lookupLatestRoundWithRewardComputation()
      } yield {
        result.value shouldBe 5L
      }
    }

    // -- computeAndStoreRewards summary tests ----------------------------------

    "computeAndStoreRewards — returns correct summary counts" in {
      for {
        (store, historyId) <- newStore()
        _ <- insertSentinelRecords(historyId, roundNumber)
        // 3 activity records, 2 parties (alice in 2 records, bob in 2)
        _ <- insertActivityRecord(
          historyId,
          roundNumber,
          Seq("alice::provider", "bob::provider"),
          Seq(3000000L, 2000000L),
        )
        _ <- insertActivityRecord(
          historyId,
          roundNumber,
          Seq("alice::provider"),
          Seq(1000000L),
        )
        _ <- insertActivityRecord(
          historyId,
          roundNumber,
          Seq("bob::provider"),
          Seq(500000L),
        )
        summary <- store.computeAndStoreRewards(roundNumber)
      } yield {
        summary.activePartiesCount shouldBe 2L
        summary.activityRecordsCount shouldBe 4L // sum of per-party counts: alice=2 + bob=2
        // TODO(#4382): update when full reward pipeline is wired into computeAndStoreRewards
        summary.rewardedPartiesCount shouldBe 0L
        summary.batchesCreatedCount shouldBe 0L
      }
    }

    "computeAndStoreRewards — empty round returns zero counts" in {
      for {
        (store, historyId) <- newStore()
        _ <- insertSentinelRecords(historyId, roundNumber)
        summary <- store.computeAndStoreRewards(roundNumber)
      } yield {
        summary.activePartiesCount shouldBe 0L
        summary.activityRecordsCount shouldBe 0L
        summary.rewardedPartiesCount shouldBe 0L
        summary.batchesCreatedCount shouldBe 0L
      }
    }

    // -- computeRewardTotals tests -------------------------------------------

    val rewardTotalsTestCases = Seq(
      // 5_000_000 / 1_000_000 * 2.0 = 10.0
      // totalIssuance = 10.0, unclaimed = 0 → thresholded = 10.0 - 0 - 10.0 = 0
      RewardTotalsTests.TestCase(
        description = "golden-value test",
        activities = Seq(Activity.alice5M),
        params = RewardIssuanceParams(
          issuancePerFeaturedAppTraffic_CCperMB = IssuanceRate.Two,
          threshold_CC = Threshold.Half,
          totalIssuanceForFeaturedAppRewards = BigDecimal("10.0"),
          unclaimedAppRewardAmount = BigDecimal("0"),
        ),
        expected = RewardTotalsTests.Expected(
          partyTotalCount = 1,
          headPartySeqNum = Some(0),
          headRewardAmount = Some(BigDecimal("10.0000000000")),
          mintingAllowance = Some(BigDecimal("10.0000000000")),
          thresholded = Some(BigDecimal("0E-10")),
          unclaimed = Some(BigDecimal("0E-10")),
          rewardedCount = Some(1L),
        ),
      ),
      // alice: 5_000_000/1M * 2.0 = 10.0 (above 0.5)
      // bob: 150_000/1M * 2.0 = 0.3 (below 0.5)
      // totalIssuance = 10.3, unclaimed = 0 → thresholded = 10.3 - 0 - 10.0 = 0.3
      RewardTotalsTests.TestCase(
        description = "below-threshold exclusion",
        activities = Seq(Activity.alice5M, Activity.bob150K),
        params = RewardIssuanceParams(
          issuancePerFeaturedAppTraffic_CCperMB = IssuanceRate.Two,
          threshold_CC = Threshold.Half,
          totalIssuanceForFeaturedAppRewards = BigDecimal("10.3"),
          unclaimedAppRewardAmount = BigDecimal("0"),
        ),
        expected = RewardTotalsTests.Expected(
          partyTotalCount = 1,
          headPartySeqNum = Some(0),
          mintingAllowance = Some(BigDecimal("10.0000000000")),
          thresholded = Some(BigDecimal("0.3000000000")),
          unclaimed = Some(BigDecimal("0E-10")),
          rewardedCount = Some(1L),
        ),
      ),
      // 250_000 / 1M * 2.0 = 0.5, exactly at threshold
      // totalIssuance = 0.5, unclaimed = 0 → thresholded = 0.5 - 0 - 0.5 = 0
      RewardTotalsTests.TestCase(
        description = "threshold boundary (exactly at threshold is included)",
        activities = Seq(Activity.alice250K),
        params = RewardIssuanceParams(
          issuancePerFeaturedAppTraffic_CCperMB = IssuanceRate.Two,
          threshold_CC = Threshold.Half,
          totalIssuanceForFeaturedAppRewards = BigDecimal("0.5"),
          unclaimedAppRewardAmount = BigDecimal("0"),
        ),
        expected = RewardTotalsTests.Expected(
          partyTotalCount = 1,
          headRewardAmount = Some(BigDecimal("0.5000000000")),
          mintingAllowance = Some(BigDecimal("0.5000000000")),
          thresholded = Some(BigDecimal("0E-10")),
          unclaimed = Some(BigDecimal("0E-10")),
          rewardedCount = Some(1L),
        ),
      ),
      // 3_333_333 / 1_000_000.0 * 2.0 = 6.666666
      // totalIssuance = 6.666666, unclaimed = 0
      RewardTotalsTests.TestCase(
        description = "decimal precision",
        activities = Seq(Activity.aliceDecimal),
        params = RewardIssuanceParams(
          issuancePerFeaturedAppTraffic_CCperMB = IssuanceRate.Two,
          threshold_CC = Threshold.Zero,
          totalIssuanceForFeaturedAppRewards = BigDecimal("6.666666"),
          unclaimedAppRewardAmount = BigDecimal("0"),
        ),
        expected = RewardTotalsTests.Expected(
          partyTotalCount = 1,
          headRewardAmount = Some(
            (BigDecimal("3333333") / BigDecimal("1000000") * BigDecimal("2.0")).setScale(10)
          ),
        ),
      ),
      // 1M / 1M * 2.0 = 2.0
      // totalIssuance = 2.0, unclaimed = 0 → thresholded = 2.0 - 0 - 2.0 = 0
      RewardTotalsTests.TestCase(
        description = "single party above threshold, round totals correct",
        activities = Seq(Activity.alice1M),
        params = RewardIssuanceParams(
          issuancePerFeaturedAppTraffic_CCperMB = IssuanceRate.Two,
          threshold_CC = Threshold.Half,
          totalIssuanceForFeaturedAppRewards = BigDecimal("2.0"),
          unclaimedAppRewardAmount = BigDecimal("0"),
        ),
        expected = RewardTotalsTests.Expected(
          partyTotalCount = 1,
          mintingAllowance = Some(BigDecimal("2.0000000000")),
          thresholded = Some(BigDecimal("0E-10")),
          unclaimed = Some(BigDecimal("0E-10")),
          rewardedCount = Some(1L),
        ),
      ),
      // alice: 100_000/1M * 2.0 = 0.2 (below 1.0)
      // bob: 50_000/1M * 2.0 = 0.1 (below 1.0)
      // totalIssuance = 0.3, unclaimed = 0 → thresholded = 0.3 - 0 - 0 = 0.3
      RewardTotalsTests.TestCase(
        description = "all parties below threshold",
        activities = Seq(Activity.alice100K, Activity.bob50K),
        params = RewardIssuanceParams(
          issuancePerFeaturedAppTraffic_CCperMB = IssuanceRate.Two,
          threshold_CC = Threshold.One,
          totalIssuanceForFeaturedAppRewards = BigDecimal("0.3"),
          unclaimedAppRewardAmount = BigDecimal("0"),
        ),
        expected = RewardTotalsTests.Expected(
          partyTotalCount = 0,
          mintingAllowance = Some(BigDecimal("0E-10")),
          thresholded = Some(BigDecimal("0.3000000000")),
          unclaimed = Some(BigDecimal("0E-10")),
          rewardedCount = Some(0L),
        ),
      ),
    )

    rewardTotalsTestCases.foreach { tc =>
      s"computeRewardTotals — ${tc.description}" in {
        RewardTotalsTests.run(tc)
      }
    }

  }

  private val verdictCounter = new java.util.concurrent.atomic.AtomicLong(1)

  /** Insert a parent row into scan_verdict_store then a child row into
    * app_activity_record_store. This satisfies the FK constraint.
    */
  private def insertActivityRecord(
      historyId: Long,
      round: Long,
      parties: Seq[String],
      weights: Seq[Long],
  ): Future[Unit] = {
    val verdictId = verdictCounter.getAndIncrement()
    val partiesArray = parties.mkString("{", ",", "}")
    val weightsArray = weights.mkString("{", ",", "}")
    futureUnlessShutdownToFuture(
      storage.underlying.queryAndUpdate(
        sqlu"""insert into scan_verdict_store
               (row_id, migration_id, domain_id, record_time, finalization_time,
                submitting_participant_uid, verdict_result, mediator_group,
                update_id, submitting_parties, transaction_root_views, history_id)
               values ($verdictId, 0, 'domain::test', $verdictId, $verdictId,
                       'participant::test', 0, 0,
                       ${"update-" + verdictId}, '{}', '{}', $historyId)""",
        "test.insertVerdictRow",
      )
    ).flatMap { _ =>
      futureUnlessShutdownToFuture(
        storage.underlying.queryAndUpdate(
          sqlu"""insert into app_activity_record_store
                 (verdict_row_id, round_number, app_provider_parties, app_activity_weights)
                 values ($verdictId, $round,
                         #${"'" + partiesArray + "'"},
                         #${"'" + weightsArray + "'"})""",
          "test.insertActivityRecord",
        )
      )
    }.map(_ => ())
  }

  /** Insert sentinel activity records for rounds adjacent to `round`, satisfying
    * the completeness precondition in aggregateActivityTotals.
    */
  private def insertSentinelRecords(historyId: Long, round: Long): Future[Unit] =
    for {
      _ <- insertActivityRecord(historyId, round - 1, Seq("sentinel::provider"), Seq(1L))
      _ <- insertActivityRecord(historyId, round + 1, Seq("sentinel::provider"), Seq(1L))
    } yield ()

  private val storeCounter = new java.util.concurrent.atomic.AtomicLong(1)

  private def newStore(): Future[(DbScanAppRewardsStore, Long)] = {
    val n = storeCounter.getAndIncrement()
    val participantId = mkParticipantId(s"rewards-test-$n")
    val updateHistory = new UpdateHistory(
      storage.underlying,
      new DomainMigrationInfo(migrationId, None),
      s"app_rewards_test_$n",
      participantId,
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      HistoryMetrics(NoOpMetricsFactory, migrationId),
    )
    updateHistory.ingestionSink.initialize().map { _ =>
      val appActivityRecordStore = new DbAppActivityRecordStore(
        storage.underlying,
        updateHistory,
        loggerFactory,
      )
      val store = new DbScanAppRewardsStore(
        storage.underlying,
        updateHistory,
        appActivityRecordStore,
        loggerFactory,
      )
      (store, updateHistory.historyId)
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)

  private object RewardTotalsTests {
    case class TestCase(
        description: String,
        activities: Seq[AppActivityPartyTotalT],
        params: RewardIssuanceParams,
        expected: Expected,
    )

    case class Expected(
        partyTotalCount: Int,
        headPartySeqNum: Option[Int] = None,
        headRewardAmount: Option[BigDecimal] = None,
        mintingAllowance: Option[BigDecimal] = None,
        thresholded: Option[BigDecimal] = None,
        unclaimed: Option[BigDecimal] = None,
        rewardedCount: Option[Long] = None,
    )

    def run(tc: TestCase): Future[org.scalatest.Assertion] =
      for {
        (partyTotals, roundTotal) <- computeRewards(
          tc.activities,
          tc.params,
        )
      } yield check(partyTotals, roundTotal, tc.expected)

    private def computeRewards(
        activities: Seq[AppActivityPartyTotalT],
        params: RewardIssuanceParams,
    ): Future[(Seq[AppRewardPartyTotalT], Option[AppRewardRoundTotalT])] =
      for {
        (store, historyId) <- newStore()
        _ <- store.insertAppActivityPartyTotals(activities.map(_.copy(historyId = historyId)))
        _ <- store.computeRewardTotals(roundNumber, params)
        partyTotals <- store.getAppRewardPartyTotalsByRound(roundNumber)
        roundTotal <- store.getAppRewardRoundTotalByRound(roundNumber)
      } yield (partyTotals, roundTotal)

    private def check(
        partyTotals: Seq[AppRewardPartyTotalT],
        roundTotal: Option[AppRewardRoundTotalT],
        expected: Expected,
    ): org.scalatest.Assertion = {
      partyTotals should have size expected.partyTotalCount.toLong
      expected.headPartySeqNum.foreach { seqNum =>
        partyTotals.head.appProviderPartySeqNum shouldBe seqNum
      }
      expected.headRewardAmount.foreach { amount =>
        partyTotals.head.totalAppRewardAmount shouldBe amount
      }
      expected.mintingAllowance.foreach { v =>
        roundTotal.value.totalAppRewardMintingAllowance shouldBe v
      }
      expected.thresholded.foreach { v =>
        roundTotal.value.totalAppRewardThresholded shouldBe v
      }
      expected.unclaimed.foreach { v =>
        roundTotal.value.totalAppRewardUnclaimed shouldBe v
      }
      expected.rewardedCount.foreach { v =>
        roundTotal.value.rewardedAppProviderPartiesCount shouldBe v
      }
      succeed
    }
  }
}

object DbScanAppRewardsStoreTest {
  private val roundNumber = 42L

  object Activity {
    val alice5M = AppActivityPartyTotalT(0L, roundNumber, 5000000L, 0, "alice::provider", 1L)
    val bob150K = AppActivityPartyTotalT(0L, roundNumber, 150000L, 1, "bob::provider", 1L)
    val alice250K = AppActivityPartyTotalT(0L, roundNumber, 250000L, 0, "alice::provider", 1L)
    val aliceDecimal = AppActivityPartyTotalT(0L, roundNumber, 3333333L, 0, "alice::provider", 1L)
    val alice1M = AppActivityPartyTotalT(0L, roundNumber, 1000000L, 0, "alice::provider", 1L)
    val alice100K = AppActivityPartyTotalT(0L, roundNumber, 100000L, 0, "alice::provider", 1L)
    val bob50K = AppActivityPartyTotalT(0L, roundNumber, 50000L, 1, "bob::provider", 1L)
  }

  object IssuanceRate {
    val Two: BigDecimal = BigDecimal("2.0")
  }

  object Threshold {
    val Zero: BigDecimal = BigDecimal("0.0")
    val Half: BigDecimal = BigDecimal("0.5")
    val One: BigDecimal = BigDecimal("1.0")
  }
  object DecimalSamples {
    val Zero: BigDecimal = BigDecimal("0")
    val One: BigDecimal = BigDecimal("1")
    val NegOne: BigDecimal = BigDecimal("-1")
    val MinPositiveFractional: BigDecimal = BigDecimal("0.0000000001")
    val MinNegativeFractional: BigDecimal = BigDecimal("-0.0000000001")
    val FractionalOnly: BigDecimal = BigDecimal("0.1234567890")
    val TypicalPositive: BigDecimal = BigDecimal("1.2345678901")
    val TypicalNegative: BigDecimal = BigDecimal("-1.2345678901")
    val LargePositive: BigDecimal = BigDecimal("12345678901234567890.1234567891")
    val MaxDecimal: BigDecimal = BigDecimal("9999999999999999999999999999.9999999999")
    val MinDecimal: BigDecimal = BigDecimal("-9999999999999999999999999999.9999999999")
  }
}
