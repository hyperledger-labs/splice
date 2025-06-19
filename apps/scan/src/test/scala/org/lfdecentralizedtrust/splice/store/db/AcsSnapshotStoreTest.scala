package org.lfdecentralizedtrust.splice.store.db

import cats.data.NonEmptyVector
import com.daml.ledger.javaapi.data.Unit as damlUnit
import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  PageLimit,
  StoreErrors,
  StoreTest,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.util.{Contract, HoldingsSummary, PackageQualifiedName}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import io.grpc.StatusRuntimeException
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.scalatest.Succeeded

import java.time.Instant
import scala.concurrent.Future
import scala.util.{Failure, Success}

class AcsSnapshotStoreTest
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  private val DefaultMigrationId = 0L
  private val timestamp1 = CantonTimestamp.Epoch.plusSeconds(3600)
  private val timestamp2 = CantonTimestamp.Epoch.plusSeconds(3600 * 2)
  private val timestamp3 = CantonTimestamp.Epoch.plusSeconds(3600 * 3)
  private val timestamp4 = CantonTimestamp.Epoch.plusSeconds(3600 * 4)
  private val timestamps = Seq(timestamp1, timestamp2, timestamp3, timestamp4)

  "AcsSnapshotStoreTest" should {

    "lookupSnapshotBefore" should {

      "return None when no snapshot is available" in {
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          result <- store.lookupSnapshotBefore(DefaultMigrationId, CantonTimestamp.MaxValue)
        } yield result should be(None)
      }

      "only return the last snapshot of the passed migration id" in {
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- ingestCreate(
            updateHistory,
            amuletRules(),
            timestamp1.minusSeconds(1L),
          )
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          result <- store.lookupSnapshotBefore(migrationId = 1L, CantonTimestamp.MaxValue)
        } yield result should be(None)
      }

      "only return the last snapshot of the active history id" in {
        for {
          originalUpdateHistory <- mkUpdateHistory(participantId = "original")
          originalStore = mkStore(originalUpdateHistory)
          activeUpdateHistory <- mkUpdateHistory(participantId = "active")
          activeStore = mkStore(activeUpdateHistory)
          _ <- ingestCreate(
            originalUpdateHistory,
            amuletRules(),
            timestamp1.minusSeconds(1L),
          )
          _ <- originalStore.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          result <- activeStore.lookupSnapshotBefore(
            migrationId = activeStore.currentMigrationId,
            CantonTimestamp.MaxValue,
          )
        } yield result should be(None)
      }

      "return the latest snapshot before the given timestamp" in {
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- MonadUtil.sequentialTraverse(Seq(timestamp1, timestamp2, timestamp3)) { timestamp =>
            for {
              _ <- ingestCreate(
                updateHistory,
                openMiningRound(dsoParty, 0L, 1.0),
                timestamp.minusSeconds(1L),
              )
              snapshot <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp)
            } yield snapshot
          }
          result <- store.lookupSnapshotBefore(DefaultMigrationId, timestamp4)
        } yield result.map(_.snapshotRecordTime) should be(Some(timestamp3))
      }

    }

    "snapshotting" should {

      "fail when attempting to create two snapshots with the same record time" in {
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- ingestCreate(
            updateHistory,
            amuletRules(),
            timestamp1.minusSeconds(1L),
          )
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          result <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1).transform {
            case Success(_) =>
              Failure(new RuntimeException("This insert shouldn't have succeeded!"))
            case Failure(ex)
                if ex.getMessage.contains(
                  "ERROR: duplicate key value violates unique constraint \"acs_snapshot_pkey\""
                ) =>
              Success("OK")
            case Failure(ex) =>
              Failure(ex)
          }
        } yield result should be("OK")
      }

      "allow two snapshots with the same record time, different migration_ids" in {
        MonadUtil
          .sequentialTraverse(Seq(1, 2)) { migrationId =>
            for {
              updateHistory <- mkUpdateHistory(migrationId = migrationId.toLong)
              store = mkStore(updateHistory, migrationId = migrationId.toLong)
              _ <- ingestCreate(
                updateHistory,
                amuletRules(),
                timestamp1.minusSeconds(1L),
              )
              _ <- store.insertNewSnapshot(None, migrationId.toLong, timestamp1)
            } yield succeed
          }
          .map(_ => succeed)
      }

      "build snapshots incrementally" in {
        // each snapshot has a new contract
        val contracts = timestamps.zipWithIndex.map { case (_, i) =>
          openMiningRound(dsoParty, (i + 3).toLong, 1.0)
        }
        val expectedContractsPerTimestamp = timestamps.zipWithIndex.map { case (tx, idx) =>
          tx -> contracts.slice(0, idx + 1)
        }.toMap

        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- MonadUtil.sequentialTraverse(
            Seq(timestamp1, timestamp2, timestamp3, timestamp4).zipWithIndex
          ) { case (timestamp, i) =>
            for {
              _ <- ingestCreate(
                updateHistory,
                contracts(i),
                timestamp.minusSeconds(10L).plusSeconds(i.toLong),
              )
              _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp)
              contractsInSnapshot <- queryAll(store, timestamp)
            } yield contractsInSnapshot.createdEventsInPage.map(_.event.getContractId) should be(
              expectedContractsPerTimestamp(timestamp).map(_.contractId.contractId)
            )
          }
        } yield Succeeded
      }

      "include the ACS in the snapshots" in {
        val acs = Seq(
          openMiningRound(dsoParty, 0L, 1.0),
          openMiningRound(dsoParty, 1L, 1.0),
          openMiningRound(dsoParty, 2L, 1.0),
        )
        val omr1 = openMiningRound(dsoParty, 3L, 1.0)
        val omr2 = openMiningRound(dsoParty, 4L, 1.0)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- ingestAcs(updateHistory, acs)
          // t1
          _ <- ingestCreate(
            updateHistory,
            omr1,
            timestamp1.minusSeconds(2L),
          )
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          // t2
          _ <- ingestCreate(
            updateHistory,
            omr2,
            timestamp2.minusSeconds(1L),
          )
          lastSnapshot <- store.lookupSnapshotBefore(DefaultMigrationId, timestamp2)
          _ <- store.insertNewSnapshot(lastSnapshot, DefaultMigrationId, timestamp2)
          result <- queryAll(store, timestamp2)
        } yield result.createdEventsInPage.map(_.event.getContractId) should be(
          (acs ++ Seq(omr1, omr2)).map(_.contractId.contractId)
        )
      }

      "exercises remove from the ACS" in {
        // t1 -> create
        // t2 -> archives
        // t3 -> creates a different one and exercises a non-consuming choice on it
        val toArchive = openMiningRound(dsoParty, 1L, 1.0)
        val alwaysThere = openMiningRound(dsoParty, 0L, 1.0) // without it there's no snapshots
        val toCreateT3 = openMiningRound(dsoParty, 3L, 1.0)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- ingestCreate(updateHistory, alwaysThere, timestamp1.minusSeconds(2L))
          _ <- ingestCreate(updateHistory, toArchive, timestamp1.minusSeconds(1L))
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          // t2
          _ <- ingestArchive(updateHistory, toArchive, timestamp2.minusSeconds(2L))
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp2)
          // t3
          _ <- ingestCreate(updateHistory, toCreateT3, timestamp3.minusSeconds(2L))
          _ <- ingestNonConsuming(updateHistory, toCreateT3, timestamp3.minusSeconds(1L))
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp3)
          // querying at the end to prove anything happening in between doesn't matters
          afterT1 <- queryAll(store, timestamp1)
          afterT2 <- queryAll(store, timestamp2)
          afterT3 <- queryAll(store, timestamp3)
        } yield {
          afterT1.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(alwaysThere, toArchive).map(_.contractId.contractId)
          )
          afterT2.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(alwaysThere).map(_.contractId.contractId)
          )
          afterT3.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(alwaysThere, toCreateT3).map(_.contractId.contractId)
          )
        }
      }

    }

    "queryAcsSnapshot" should {

      "filter by party" in {
        val p1 = openMiningRound(dsoParty, 1L, 1.0)
        val p2 = openMiningRound(dsoParty, 2L, 1.0)
        val bothParties = openMiningRound(dsoParty, 3L, 1.0)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- ingestCreate(
            updateHistory,
            p1,
            timestamp1.minusSeconds(3L),
            signatories = Seq(providerParty(1)),
          )
          _ <- ingestCreate(
            updateHistory,
            p2,
            timestamp1.minusSeconds(2L),
            signatories = Seq(providerParty(2)),
          )
          _ <- ingestCreate(
            updateHistory,
            bothParties,
            timestamp1.minusSeconds(1L),
            signatories = Seq(providerParty(1), providerParty(2)),
          )
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          resultParty1 <- store.queryAcsSnapshot(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            Seq(providerParty(1)),
            Seq.empty,
          )
          resultParty2 <- store.queryAcsSnapshot(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            Seq(providerParty(2)),
            Seq.empty,
          )
          resultBothParties <- store.queryAcsSnapshot(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            Seq(providerParty(1), providerParty(2)),
            Seq.empty,
          )
        } yield {
          resultParty1.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(p1, bothParties).map(_.contractId.contractId)
          )
          resultParty2.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(p2, bothParties).map(_.contractId.contractId)
          )
          resultBothParties.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(p1, p2, bothParties).map(_.contractId.contractId)
          )
        }
      }

      "filter by template id" in {
        val t1 = openMiningRound(dsoParty, 1L, 1.0)
        val t2 = closedMiningRound(dsoParty, 2L)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- ingestCreate(
            updateHistory,
            t1,
            timestamp1.minusSeconds(2L),
            signatories = Seq(dsoParty),
          )
          _ <- ingestCreate(
            updateHistory,
            t2,
            timestamp1.minusSeconds(1L),
            signatories = Seq(dsoParty),
          )
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          resultTemplate1 <- store.queryAcsSnapshot(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            Seq.empty,
            Seq(PackageQualifiedName(t1.identifier)),
          )
          resultTemplate2 <- store.queryAcsSnapshot(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            Seq.empty,
            Seq(PackageQualifiedName(t2.identifier)),
          )
        } yield {
          resultTemplate1.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(t1).map(_.contractId.contractId)
          )
          resultTemplate2.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(t2).map(_.contractId.contractId)
          )
        }
      }

      "filter by both" in {
        val ok = openMiningRound(providerParty(1), 1L, 1.0)
        val differentParty = openMiningRound(providerParty(2), 2L, 1.0)
        val differentTemplate = closedMiningRound(providerParty(1), 3L)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- ingestCreate(
            updateHistory,
            ok,
            timestamp1.minusSeconds(3L),
            signatories = Seq(providerParty(1)),
          )
          _ <- ingestCreate(
            updateHistory,
            differentParty,
            timestamp1.minusSeconds(2L),
            signatories = Seq(providerParty(2)),
          )
          _ <- ingestCreate(
            updateHistory,
            differentTemplate,
            timestamp1.minusSeconds(1L),
            signatories = Seq(providerParty(1)),
          )
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          result <- store.queryAcsSnapshot(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            Seq(providerParty(1)),
            Seq(PackageQualifiedName(ok.identifier)),
          )
        } yield {
          result.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(ok).map(_.contractId.contractId)
          )
        }
      }

      "paginate" in {
        val contracts = (1 to 10).map { i =>
          openMiningRound(dsoParty, i.toLong, 1.0)
        }
        def queryRecursive(
            store: AcsSnapshotStore,
            after: Option[Long],
            acc: Vector[String],
        ): Future[Vector[String]] = {
          store
            .queryAcsSnapshot(
              DefaultMigrationId,
              timestamp1,
              after,
              PageLimit.tryCreate(1),
              Seq.empty,
              Seq.empty,
            )
            .flatMap { result =>
              val newAcc = acc ++ result.createdEventsInPage.map(_.event.getContractId)
              result.afterToken match {
                case Some(value) => queryRecursive(store, Some(value), newAcc)
                case None => Future.successful(newAcc)
              }
            }
        }
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          // t1
          _ <- MonadUtil.sequentialTraverse(contracts.zipWithIndex) { case (contract, i) =>
            ingestCreate(
              updateHistory,
              contract,
              timestamp1.minusSeconds(10L - i.toLong),
            )
          }
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          result <- queryRecursive(store, None, Vector.empty)
        } yield {
          result should be(contracts.map(_.contractId.contractId))
        }
      }

    }

    "getHoldingsState" should {

      "only include contracts where the parties provided are owners, not just stakeholders" in {
        val wantedParty1 = providerParty(1)
        val wantedParty2 = providerParty(2)
        val ignoredParty = providerParty(3)
        // We include amulets and locked amulets in an older version
        // as a regression test for #14758
        val amulet1 = amulet(wantedParty1, 10, 1L, 1.0, version = DarResources.amulet_0_1_4)
        val amulet2 = amulet(wantedParty2, 20, 2L, 1.0)
        val ignoredAmulet = amulet(ignoredParty, 666, 1L, 1.0)
        val lockedAmulet1 =
          lockedAmulet(wantedParty1, 30, 1L, 0.5, version = DarResources.amulet_0_1_4)
        val lockedAmulet2 = lockedAmulet(wantedParty2, 40, 2L, 0.5)
        val ignoredLocked = lockedAmulet(ignoredParty, 666, 1, 0.5)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- MonadUtil.sequentialTraverse(Seq(amulet1, amulet2, ignoredAmulet)) { amulet =>
            ingestCreate(
              updateHistory,
              amulet,
              timestamp1.minusSeconds(10L),
              Seq(PartyId.tryFromProtoPrimitive(amulet.payload.owner), dsoParty),
            )
          }
          _ <- MonadUtil.sequentialTraverse(Seq(lockedAmulet1, lockedAmulet2, ignoredLocked)) {
            locked =>
              ingestCreate(
                updateHistory,
                locked,
                timestamp1.minusSeconds(10L),
                Seq(PartyId.tryFromProtoPrimitive(locked.payload.amulet.owner), dsoParty),
              )
          }
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          resultDso <- store.getHoldingsState(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            NonEmptyVector.of(dsoParty),
          )
          resultWanteds <- store.getHoldingsState(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            NonEmptyVector.of(wantedParty1, wantedParty2),
          )
        } yield {
          resultDso.createdEventsInPage should be(empty)
          resultWanteds.createdEventsInPage.map(_.event.getContractId).toSet should be(
            Set(amulet1, amulet2, lockedAmulet1, lockedAmulet2).map(_.contractId.contractId)
          )
        }
      }

      "lock holders don't see locked coins where they're not the owner" in {
        val owner = providerParty(1)
        val holder = providerParty(2)
        val amulet = lockedAmulet(owner, 10, 1L, 0.5)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- ingestCreate(
            updateHistory,
            amulet,
            timestamp1.minusSeconds(10L),
            Seq(owner, holder),
          )
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          resultOwner <- store.getHoldingsState(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            NonEmptyVector.of(owner),
          )
          resultHolder <- store.getHoldingsState(
            DefaultMigrationId,
            timestamp1,
            None,
            PageLimit.tryCreate(10),
            NonEmptyVector.of(holder),
          )
        } yield {
          resultHolder.createdEventsInPage should be(empty)
          resultOwner.createdEventsInPage.map(_.event.getContractId) should be(
            Seq(amulet.contractId.contractId)
          )
        }
      }

      "fail if too many contracts were returned by HardLimit" in {
        val owner = providerParty(1)
        val holder = providerParty(2)
        val amulet1 = lockedAmulet(owner, 10, 1L, 0.5)
        val amulet2 = lockedAmulet(owner, 10, 2L, 0.5)
        (for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- ingestCreate(
            updateHistory,
            amulet1,
            timestamp1.minusSeconds(10L),
            Seq(owner, holder),
          )
          _ <- ingestCreate(
            updateHistory,
            amulet2,
            timestamp1.minusSeconds(10L),
            Seq(owner, holder),
          )
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          _result <- store.getHoldingsState(
            DefaultMigrationId,
            timestamp1,
            None,
            HardLimit.tryCreate(1),
            NonEmptyVector.of(owner),
          )
        } yield fail("should not get here, call should've failed")).failed.futureValue shouldBe a[
          StatusRuntimeException
        ]
      }

    }

    "getHoldingsSummary" should {

      "return the summary for the given parties in the given snapshot; computed as of provided round" in {
        val wantedParty1 = providerParty(1)
        val wantedParty2 = providerParty(2)
        val ignoredParty = providerParty(3)
        val amulets1 = (1 to 3).map(n => amulet(wantedParty1, 10, n.toLong, 1.0))
        val amulets2 = (1 to 3).map(n => amulet(wantedParty2, 20, n.toLong, 1.0))
        val ignoredAmulet = amulet(ignoredParty, 666, 1, 1.0)
        val lockedAmulets1 = (1 to 3).map(n => lockedAmulet(wantedParty1, 30, n.toLong, 0.5))
        val lockedAmulets2 = (1 to 3).map(n => lockedAmulet(wantedParty2, 40, n.toLong, 0.5))
        val ignoredLocked = lockedAmulet(ignoredParty, 666, 1, 0.5)
        for {
          updateHistory <- mkUpdateHistory()
          store = mkStore(updateHistory)
          _ <- MonadUtil.sequentialTraverse(amulets1 ++ amulets2 :+ ignoredAmulet) { amulet =>
            ingestCreate(
              updateHistory,
              amulet,
              timestamp1.minusSeconds(10L),
              Seq(PartyId.tryFromProtoPrimitive(amulet.payload.owner)),
            )
          }
          _ <- MonadUtil.sequentialTraverse(lockedAmulets1 ++ lockedAmulets2 :+ ignoredLocked) {
            locked =>
              ingestCreate(
                updateHistory,
                locked,
                timestamp1.minusSeconds(5L),
                Seq(PartyId.tryFromProtoPrimitive(locked.payload.amulet.owner)),
              )
          }
          _ <- store.insertNewSnapshot(None, DefaultMigrationId, timestamp1)
          summaryAtRound3 <- store.getHoldingsSummary(
            DefaultMigrationId,
            timestamp1,
            NonEmptyVector.of(wantedParty1, wantedParty2),
            asOfRound = 3L,
          )
          summaryAtRound10 <- store.getHoldingsSummary(
            DefaultMigrationId,
            timestamp1,
            NonEmptyVector.of(wantedParty1, wantedParty2),
            asOfRound = 10L,
          )
          summaryAtRound100 <- store.getHoldingsSummary(
            DefaultMigrationId,
            timestamp1,
            NonEmptyVector.of(wantedParty1, wantedParty2),
            asOfRound = 100L,
          )
        } yield {
          summaryAtRound3 should be(
            AcsSnapshotStore.HoldingsSummaryResult(
              DefaultMigrationId,
              timestamp1,
              3L,
              Map(
                wantedParty1 -> HoldingsSummary(
                  totalUnlockedCoin = 10 * 3,
                  totalLockedCoin = 30 * 3,
                  totalCoinHoldings = 10 * 3 + 30 * 3,
                  accumulatedHoldingFeesUnlocked = 1.0 * 2 + 1.0 * 1,
                  accumulatedHoldingFeesLocked = 0.5 * 2 + 0.5 * 1,
                  accumulatedHoldingFeesTotal = 1.0 * 2 + 1.0 * 1 + 0.5 * 2 + 0.5 * 1,
                  totalAvailableCoin = (10 * 3) - (1.0 * 2 + 1.0 * 1),
                ),
                wantedParty2 -> HoldingsSummary(
                  totalUnlockedCoin = 20 * 3,
                  totalLockedCoin = 40 * 3,
                  totalCoinHoldings = 20 * 3 + 40 * 3,
                  accumulatedHoldingFeesUnlocked = 1.0 * 2 + 1.0 * 1,
                  accumulatedHoldingFeesLocked = 0.5 * 2 + 0.5 * 1,
                  accumulatedHoldingFeesTotal = 1.0 * 2 + 1.0 * 1 + 0.5 * 2 + 0.5 * 1,
                  totalAvailableCoin = (20 * 3) - (1.0 * 2 + 1.0 * 1),
                ),
              ),
            )
          )
          summaryAtRound10 should be(
            AcsSnapshotStore.HoldingsSummaryResult(
              DefaultMigrationId,
              timestamp1,
              10L,
              Map(
                wantedParty1 -> HoldingsSummary(
                  totalUnlockedCoin = 10 * 3,
                  totalLockedCoin = 30 * 3,
                  totalCoinHoldings = 10 * 3 + 30 * 3,
                  accumulatedHoldingFeesUnlocked = 1.0 * 9 + 1.0 * 8 + 1.0 * 7,
                  accumulatedHoldingFeesLocked = 0.5 * 9 + 0.5 * 8 + 0.5 * 7,
                  accumulatedHoldingFeesTotal =
                    1.0 * 9 + 1.0 * 8 + 1.0 * 7 + 0.5 * 9 + 0.5 * 8 + 0.5 * 7,
                  totalAvailableCoin = (10 * 3) - (1.0 * 9 + 1.0 * 8 + 1.0 * 7),
                ),
                wantedParty2 -> HoldingsSummary(
                  totalUnlockedCoin = 20 * 3,
                  totalLockedCoin = 40 * 3,
                  totalCoinHoldings = 20 * 3 + 40 * 3,
                  accumulatedHoldingFeesUnlocked = 1.0 * 9 + 1.0 * 8 + 1.0 * 7,
                  accumulatedHoldingFeesLocked = 0.5 * 9 + 0.5 * 8 + 0.5 * 7,
                  accumulatedHoldingFeesTotal =
                    1.0 * 9 + 1.0 * 8 + 1.0 * 7 + 0.5 * 9 + 0.5 * 8 + 0.5 * 7,
                  totalAvailableCoin = (20 * 3) - (1.0 * 9 + 1.0 * 8 + 1.0 * 7),
                ),
              ),
            )
          )
          summaryAtRound100 should be(
            AcsSnapshotStore.HoldingsSummaryResult(
              DefaultMigrationId,
              timestamp1,
              100L,
              Map(
                wantedParty1 -> HoldingsSummary(
                  totalUnlockedCoin = 10 * 3,
                  totalLockedCoin = 30 * 3,
                  totalCoinHoldings = 10 * 3 + 30 * 3,
                  accumulatedHoldingFeesUnlocked = 10 * 3,
                  accumulatedHoldingFeesLocked = 30 * 3,
                  accumulatedHoldingFeesTotal = 10 * 3 + 30 * 3,
                  totalAvailableCoin = 0,
                ),
                wantedParty2 -> HoldingsSummary(
                  totalUnlockedCoin = 20 * 3,
                  totalLockedCoin = 40 * 3,
                  totalCoinHoldings = 20 * 3 + 40 * 3,
                  accumulatedHoldingFeesUnlocked = 20 * 3,
                  accumulatedHoldingFeesLocked = 40 * 3,
                  accumulatedHoldingFeesTotal = 20 * 3 + 40 * 3,
                  totalAvailableCoin = 0,
                ),
              ),
            )
          )
        }
      }

    }

    "fix for corrupt snapshots" should {
      "remove corrupt snapshots" in {
        val firstMigration = 1L
        val secondMigration = 2L
        for {
          // Initial migration
          updateHistory1 <- mkUpdateHistory(
            firstMigration,
            backfillingRequired = BackfillingRequirement.NeedsBackfilling,
          )
          store1 = mkStore(updateHistory1)
          migrationsWithCorruptSnapshots1 <- store1.updateHistory.migrationsWithCorruptSnapshots()
          _ = migrationsWithCorruptSnapshots1 shouldBe Set.empty
          update1 <- ingestCreate(
            updateHistory1,
            amuletRules(),
            timestamp1.minusSeconds(1L),
          )
          _ <- store1.insertNewSnapshot(None, firstMigration, timestamp1)

          // Second migration. This is missing the import update corresponding to the create above.
          updateHistory2 <- mkUpdateHistory(
            secondMigration,
            backfillingRequired = BackfillingRequirement.NeedsBackfilling,
          )
          store2 = mkStore(updateHistory2)
          _ <- ingestCreate(
            updateHistory2,
            amuletRules(),
            timestamp2.minusSeconds(1L),
          )

          // This snapshot on the second migration is corrupt, it should be possible to detect and delete it
          _ <- store2.insertNewSnapshot(None, secondMigration, timestamp2)

          migrationsWithCorruptSnapshots2 <- store2.updateHistory.migrationsWithCorruptSnapshots()
          _ = migrationsWithCorruptSnapshots2 shouldBe Set(secondMigration)

          corruptSnapshot <- store2.lookupSnapshotBefore(secondMigration, CantonTimestamp.MaxValue)
          _ = corruptSnapshot.value.snapshotRecordTime shouldBe timestamp2

          _ <- store2.deleteSnapshot(corruptSnapshot.value)
          corruptSnapshotAfterDelete <- store2.lookupSnapshotBefore(
            secondMigration,
            CantonTimestamp.MaxValue,
          )
          _ = corruptSnapshotAfterDelete should be(empty)

          // Ingest some import update to simulate the import update backfilling and re-create the snapshot
          _ <- ingestCreate(
            updateHistory2,
            amuletRules(),
            CantonTimestamp.MinValue,
          )
          _ <- store2.insertNewSnapshot(None, secondMigration, timestamp2)

          migrationsWithCorruptSnapshots3 <- store2.updateHistory.migrationsWithCorruptSnapshots()
          _ = migrationsWithCorruptSnapshots3 shouldBe Set.empty
        } yield succeed
      }
    }
  }

  private def mkUpdateHistory(
      migrationId: Long = DefaultMigrationId,
      participantId: String = "whatever",
      // Default to backfilling being always complete, to avoid unnecessary complexity in the tests
      backfillingRequired: BackfillingRequirement = BackfillingRequirement.BackfillingNotRequired,
  ): Future[UpdateHistory] = {
    val updateHistory = new UpdateHistory(
      storage.underlying, // not under test
      new DomainMigrationInfo(migrationId, None),
      "update_history_acs_snapshot_test",
      mkParticipantId(participantId),
      dsoParty,
      backfillingRequired,
      loggerFactory,
      true,
    )
    updateHistory.ingestionSink.initialize().map(_ => updateHistory)
  }

  private def mkStore(
      updateHistory: UpdateHistory,
      migrationId: Long = DefaultMigrationId,
  ): AcsSnapshotStore = {
    new AcsSnapshotStore(
      // we're guaranteed to only execute the insert once (in the context of AcsSnapshotTrigger),
      // and the insert query is already complicated enough as-is, so I'm not gonna make it worse just for tests.
      storage.underlying,
      updateHistory,
      migrationId,
      loggerFactory,
    )
  }

  private def ingestCreate[TCid <: ContractId[T], T](
      updateHistory: UpdateHistory,
      create: Contract[TCid, T],
      recordTime: CantonTimestamp,
      signatories: Seq[PartyId] = Seq(dsoParty),
  ): Future[TransactionTreeUpdate] = {
    val update = TransactionTreeUpdate(
      mkCreateTx(
        nextOffset(),
        Seq(create.copy(createdAt = recordTime.toInstant).asInstanceOf[Contract[TCid, T]]),
        Instant.now(),
        signatories,
        dummyDomain,
        "acs-snapshot-store-test",
        recordTime.toInstant,
        packageName = DarResources
          .lookupPackageId(create.identifier.getPackageId)
          .getOrElse(
            throw new IllegalArgumentException(
              s"No package found for template ${create.identifier}"
            )
          )
          .metadata
          .name,
      )
    )
    updateHistory.ingestionSink
      .ingestUpdate(
        dummyDomain,
        update,
      )
      .map(_ => update)
  }

  private def ingestArchive[TCid <: ContractId[T], T](
      updateHistory: UpdateHistory,
      archive: Contract[TCid, T],
      recordTime: CantonTimestamp,
  ): Future[Unit] = {
    updateHistory.ingestionSink.ingestUpdate(
      dummyDomain,
      TransactionTreeUpdate(
        mkTx(
          nextOffset(),
          Seq(toArchivedEvent(archive)),
          dummyDomain,
          recordTime = recordTime.toInstant,
        )
      ),
    )
  }

  private def ingestNonConsuming[TCid <: ContractId[T], T](
      updateHistory: UpdateHistory,
      contract: Contract[TCid, T],
      recordTime: CantonTimestamp,
  ): Future[Unit] = {
    updateHistory.ingestionSink.ingestUpdate(
      dummyDomain,
      TransactionTreeUpdate(
        mkTx(
          nextOffset(),
          Seq(
            exercisedEvent(
              contractId = contract.contractId.contractId,
              templateId = contract.identifier,
              interfaceId = None,
              choice = "nonConsumingChoice",
              consuming = false,
              argument = damlUnit.getInstance(),
              result = damlUnit.getInstance(),
            )
          ),
          dummyDomain,
          recordTime = recordTime.toInstant,
        )
      ),
    )
  }

  private def ingestAcs[TCid <: ContractId[T], T](
      updateHistory: UpdateHistory,
      acs: Seq[Contract[TCid, T]],
  ): Future[Unit] = {
    MonadUtil
      .sequentialTraverse(acs) { contract =>
        ingestCreate(updateHistory, contract, recordTime = CantonTimestamp.MinValue)
      }
      .map(_ => ())
  }

  private def queryAll(
      store: AcsSnapshotStore,
      timestamp: CantonTimestamp,
      migrationId: Long = DefaultMigrationId,
  ): Future[AcsSnapshotStore.QueryAcsSnapshotResult] = {
    store.queryAcsSnapshot(
      migrationId,
      timestamp,
      None,
      PageLimit.tryCreate(1000),
      Seq.empty,
      Seq.empty,
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()

}
