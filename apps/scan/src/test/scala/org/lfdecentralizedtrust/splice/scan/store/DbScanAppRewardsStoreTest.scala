package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore.*
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, StoreTestBase, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.google.protobuf.ByteString

import scala.concurrent.Future

class DbScanAppRewardsStoreTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  private val migrationId = 0L
  private val roundNumber = 42L

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
        )
        _ <- store.insertAppActivityPartyTotals(Seq(row))
        loaded <- store.getAppActivityPartyTotalsByRound(historyId, roundNumber)
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
        )
        _ <- store.insertAppActivityRoundTotals(Seq(row))
        loaded <- store.getAppActivityRoundTotalByRound(historyId, roundNumber)
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
        )
        _ <- store.insertAppActivityPartyTotals(Seq(activityRow))
        rewardRow = AppRewardPartyTotalT(
          historyId = historyId,
          roundNumber = roundNumber,
          appProviderPartySeqNum = 0,
          totalAppRewardAmount = BigDecimal("12345678901234567890.1234567891"),
        )
        _ <- store.insertAppRewardPartyTotals(Seq(rewardRow))
        loaded <- store.getAppRewardPartyTotalsByRound(historyId, roundNumber)
      } yield {
        loaded should have size 1
        loaded.head shouldBe rewardRow
        // Verify decimal precision round-trips correctly
        loaded.head.totalAppRewardAmount shouldBe BigDecimal("12345678901234567890.1234567891")
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
        loaded <- store.getAppRewardRoundTotalByRound(historyId, roundNumber)
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
        loaded <- store.getAppRewardBatchHashesByRound(historyId, roundNumber)
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
        loaded <- store.getAppRewardRootHashByRound(historyId, roundNumber)
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
          )
        }
        _ <- store.insertAppActivityPartyTotals(rows)
        loaded <- store.getAppActivityPartyTotalsByRound(historyId, roundNumber)
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
        loaded <- store.getAppRewardBatchHashesByRound(historyId, roundNumber)
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
  }

  private def newStore(): Future[(DbScanAppRewardsStore, Long)] = {
    val participantId = mkParticipantId("rewards-test")
    val updateHistory = new UpdateHistory(
      storage.underlying,
      new DomainMigrationInfo(migrationId, None),
      "app_rewards_test",
      participantId,
      dsoParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      HistoryMetrics(NoOpMetricsFactory, migrationId),
    )
    updateHistory.ingestionSink.initialize().map { _ =>
      val store = new DbScanAppRewardsStore(
        storage.underlying,
        loggerFactory,
      )
      (store, updateHistory.historyId)
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}
