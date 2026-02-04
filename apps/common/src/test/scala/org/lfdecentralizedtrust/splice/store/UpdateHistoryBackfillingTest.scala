package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement.BackfillingNotRequired
import org.lfdecentralizedtrust.splice.util.DomainRecordTimeRange

import scala.concurrent.Future

import UpdateHistory.UpdateHistoryResponse

class UpdateHistoryBackfillingTest extends UpdateHistoryStoreTestBase {

  "UpdateHistory" should {

    "backfilling" should {

      "copy data between histories" in {
        val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
        val storeA1 = mkStore(domainMigrationId = 1, participantId = participant1)
        val storeA2 = mkStore(domainMigrationId = 2, participantId = participant1)
        val storeB2 = mkStore(domainMigrationId = 2, participantId = participant2)
        for {
          // Store A ingests all of migration 0, with updates on both domains
          _ <- initStore(storeA0)
          tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          _ <- create(domain2, validContractId(2), validOffset(2), party1, storeA0, time(2))
          _ <- create(domain1, validContractId(3), validOffset(3), party1, storeA0, time(3))
          // Store A ingests all of migration 1, with updates on only one domain
          _ <- initStore(storeA1)
          _ <- create(domain1, validContractId(4), validOffset(4), party1, storeA1, time(4))
          _ <- create(domain1, validContractId(5), validOffset(5), party1, storeA1, time(5))
          // Store A ingests one update in migration 2
          _ <- initStore(storeA2)
          _ <- create(domain2, validContractId(6), validOffset(6), party1, storeA2, time(6))
          // Store B joins it and now both stores ingest two updates on migration 2
          _ <- initStore(storeB2)
          tx2 <- createMulti(
            domain1,
            validContractId(7),
            validOffset(7),
            party1,
            Seq(storeA2, storeB2),
            time(7),
          )
          _ <- createMulti(
            domain2,
            validContractId(8),
            validOffset(8),
            party1,
            Seq(storeA2, storeB2),
            time(8),
          )
          // Mark history in store A as complete
          _ <- storeA0.initializeBackfilling(0, domain1, tx1.getUpdateId, complete = true)
          _ <- storeB2.initializeBackfilling(2, domain1, tx2.getUpdateId, complete = false)
          // Backfill
          backfiller = mkBackfilling(source = storeA2, destination = storeB2, 2)
          _ <- backfillAll(backfiller)
          // Check that the updates are the same
          updatesA <- storeA2.getAllUpdates(
            None,
            PageLimit.tryCreate(1000),
          )
          updatesB <- storeB2.getAllUpdates(
            None,
            PageLimit.tryCreate(1000),
          )
          infoB2 <- storeB2.sourceHistory.migrationInfo(0)
        } yield {
          infoB2.value.complete shouldBe true
          updatesA.map(_.update.update.updateId) should contain theSameElementsAs updatesB.map(
            _.update.update.updateId
          )
        }
      }

      "return source info if backfilling is not required" in {
        val storeA0 = mkStore(
          domainMigrationId = 13,
          participantId = participant1,
          backfillingRequired = BackfillingNotRequired,
        )
        for {
          // Create a store that has ingested some updates
          _ <- initStore(storeA0)
          _ <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          _ <- create(domain2, validContractId(2), validOffset(2), party1, storeA0, time(2))
          // If the store doesn't need backfilling, it should return the correct info
          // without explicit initialization of backfilling
          infoS <- storeA0.sourceHistory.migrationInfo(13)
        } yield {
          infoS.value.complete shouldBe true
          infoS.value.recordTimeRange shouldBe Map(
            domain1 -> DomainRecordTimeRange(time(1), time(1)),
            domain2 -> DomainRecordTimeRange(time(2), time(2)),
          )
          infoS.value.previousMigrationId shouldBe None
        }
      }

      "not return anything before initialization" in {
        val storeA0 = mkStore(domainMigrationId = 13, participantId = participant1)
        for {
          // Create a store that has ingested some updates
          _ <- initStore(storeA0)
          tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          _ <- create(domain2, validContractId(2), validOffset(2), party1, storeA0, time(2))
          // Before initializing backfilling, it should not return any data
          infoS1 <- storeA0.sourceHistory.migrationInfo(13)
          infoD1 <- storeA0.destinationHistory.backfillingInfo
          // After initializing backfilling, it should return the correct data
          _ <- storeA0.initializeBackfilling(13, domain1, tx1.getUpdateId, complete = true)
          infoS2 <- storeA0.sourceHistory.migrationInfo(13)
          infoD2 <- storeA0.destinationHistory.backfillingInfo
        } yield {
          infoS1 shouldBe None
          infoD1 shouldBe None
          infoS2.value.complete shouldBe true
          infoD2.value.migrationId shouldBe 13
        }
      }

      "not return anything for a non-existent migration" in {
        val storeA0 = mkStore(domainMigrationId = 13, participantId = participant1)
        for {
          _ <- initStore(storeA0)
          tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          _ <- create(domain2, validContractId(2), validOffset(2), party1, storeA0, time(2))
          _ <- storeA0.initializeBackfilling(13, domain1, tx1.getUpdateId, complete = true)
          info12 <- storeA0.sourceHistory.migrationInfo(12)
          info13 <- storeA0.sourceHistory.migrationInfo(13)
          info14 <- storeA0.sourceHistory.migrationInfo(14)
        } yield {
          info12 shouldBe None
          inside(info13) { case Some(s: SourceMigrationInfo) =>
            s.complete shouldBe true
            s.previousMigrationId shouldBe None
          }
          info14 shouldBe None
        }
      }

      "handle non-consecutive migration ids" in {
        // In this test, the founding migration has id 2, and is followed by migration id 5.
        val storeA2 = mkStore(domainMigrationId = 2, participantId = participant1)
        val storeA5 = mkStore(domainMigrationId = 5, participantId = participant1)
        val storeB5 = mkStore(domainMigrationId = 5, participantId = participant2)
        for {
          // Store A ingests all of migration 2
          _ <- initStore(storeA2)
          tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA2, time(1))
          _ <- create(domain2, validContractId(2), validOffset(2), party1, storeA2, time(2))
          _ <- create(domain1, validContractId(3), validOffset(3), party1, storeA2, time(3))
          // Store A ingests all of migration 5
          _ <- initStore(storeA5)
          _ <- create(domain1, validContractId(4), validOffset(4), party1, storeA5, time(4))
          _ <- create(domain1, validContractId(5), validOffset(5), party1, storeA5, time(5))
          // Store B joins and ingests one update
          _ <- initStore(storeB5)
          tx2 <- createMulti(
            domain1,
            validContractId(6),
            validOffset(6),
            party1,
            Seq(storeA5, storeB5),
            time(6),
          )
          // Mark history in store A as complete
          _ <- storeA5.initializeBackfilling(2, domain1, tx1.getUpdateId, complete = true)
          _ <- storeB5.initializeBackfilling(5, domain1, tx2.getUpdateId, complete = false)
          // Backfill
          backfiller = mkBackfilling(source = storeA5, destination = storeB5, 5)
          _ <- backfillAll(backfiller)
          // Check that the updates are the same
          updatesA <- storeA5.getAllUpdates(
            None,
            PageLimit.tryCreate(1000),
          )
          updatesB <- storeB5.getAllUpdates(
            None,
            PageLimit.tryCreate(1000),
          )
        } yield {
          updatesA.map(_.update.update.updateId) should contain theSameElementsAs updatesB.map(
            _.update.update.updateId
          )
        }
      }
    }

    "importUpdatesBackfillingInfo" should {
      "return None if backfilling is not initialized" in {
        val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
        for {
          _ <- initStore(storeA0)

          info <- storeA0.destinationHistory.importUpdatesBackfillingInfo
        } yield {
          info shouldBe None
        }
      }

      "return correct value for (backfilling fully complete, now in first migration)" in {
        val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
        for {
          _ <- initStore(storeA0)
          tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          _ <- storeA0.initializeBackfilling(0, domain1, tx1.getUpdateId, complete = true)

          info <- storeA0.destinationHistory.importUpdatesBackfillingInfo
        } yield {
          info.value.migrationId shouldBe 0
          info.value.lastUpdateId shouldBe None
        }
      }

      "return correct value for (backfilling fully complete, now in second migration)" in {
        val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
        val storeA1 = mkStore(domainMigrationId = 1, participantId = participant1)
        for {
          _ <- initStore(storeA0)
          _ <- initStore(storeA1)

          tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          _ <- importUpdate(tx1, validOffset(2), storeA1)
          _ <- create(domain1, validContractId(3), validOffset(3), party1, storeA1, time(3))

          _ <- storeA1.initializeBackfilling(0, domain1, tx1.getUpdateId, complete = true)

          info <- storeA0.destinationHistory.importUpdatesBackfillingInfo
        } yield {
          info.value.migrationId shouldBe 0
          info.value.lastUpdateId shouldBe None
        }
      }

      "return correct value for (update backfilling complete, now in first migration)" in {
        val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
        for {
          _ <- initStore(storeA0)

          _ <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          tx2 <- create(domain1, validContractId(2), validOffset(2), party1, storeA0, time(2))

          _ <- storeA0.initializeBackfilling(0, domain1, tx2.getUpdateId, complete = false)
          _ <- storeA0.destinationHistory.markBackfillingComplete()

          info <- storeA0.destinationHistory.importUpdatesBackfillingInfo
        } yield {
          info.value.migrationId shouldBe 0
          info.value.lastUpdateId shouldBe None
        }
      }

      "return correct value for (update backfilling complete, now in second migration)" in {
        val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
        val storeA1 = mkStore(domainMigrationId = 1, participantId = participant1)
        for {
          _ <- initStore(storeA0)
          _ <- initStore(storeA1)

          // One update in each migration, but the import update is missing
          tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          _ <- create(domain1, validContractId(3), validOffset(3), party1, storeA1, time(3))

          _ <- storeA1.initializeBackfilling(0, domain1, tx1.getUpdateId, complete = false)
          _ <- storeA1.destinationHistory.markBackfillingComplete()

          info <- storeA1.destinationHistory.importUpdatesBackfillingInfo
        } yield {
          info.value.migrationId shouldBe 1
          info.value.lastUpdateId shouldBe None
        }
      }

      "return correct value for (import backfilling in progress, now in second migration)" in {
        val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
        val storeA1 = mkStore(domainMigrationId = 1, participantId = participant1)
        for {
          _ <- initStore(storeA0)
          _ <- initStore(storeA1)

          // One update in each migration, with the corresponding import update,
          // but the import backfilling is not marked complete, so we don't know if there are more import updates.
          tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          itx <- importUpdate(tx1, validOffset(2), storeA1)
          _ <- create(domain1, validContractId(3), validOffset(3), party1, storeA1, time(3))

          _ <- storeA1.initializeBackfilling(0, domain1, tx1.getUpdateId, complete = false)
          _ <- storeA1.destinationHistory.markBackfillingComplete()

          info <- storeA1.destinationHistory.importUpdatesBackfillingInfo
        } yield {
          info.value.migrationId shouldBe 1
          info.value.lastUpdateId shouldBe Some(
            itx.getEventsById.values().iterator().next().getContractId
          )
        }
      }

      "return correct last update id" in {
        val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
        val storeA1 = mkStore(domainMigrationId = 1, participantId = participant1)
        for {
          _ <- initStore(storeA0)
          _ <- initStore(storeA1)

          // 10 updates in migration 0, with corresponding import updates in migration 1
          txs <- MonadUtil.sequentialTraverse((1 to 10).toList) { i =>
            create(domain1, validContractId(i), validOffset(i), party1, storeA0, time(i))
          }
          itxs <- MonadUtil.sequentialTraverse(txs.zipWithIndex) { case (tx, i) =>
            importUpdate(tx, validOffset(10 + i), storeA1)
          }
          _ <- create(domain1, validContractId(30), validOffset(30), party1, storeA1, time(30))

          _ <- storeA1.initializeBackfilling(
            0,
            domain1,
            txs.headOption.value.getUpdateId,
            complete = false,
          )
          _ <- storeA1.destinationHistory.markBackfillingComplete()

          info <- storeA1.destinationHistory.importUpdatesBackfillingInfo
        } yield {
          info.value.migrationId shouldBe 1
          info.value.lastUpdateId shouldBe Some(
            itxs.map(_.getEventsById.values().iterator().next().getContractId).max
          )
        }
      }
    }
  }

  private def mkBackfilling(
      source: UpdateHistory,
      destination: UpdateHistory,
      latestMigrationId: Long,
  ) =
    new HistoryBackfilling[UpdateHistoryResponse](
      destination.destinationHistory,
      source.sourceHistory,
      latestMigrationId,
      batchSize = 10,
      loggerFactory = loggerFactory,
    )

  private def backfillAll(
      backfiller: HistoryBackfilling[UpdateHistoryResponse]
  ): Future[Unit] = {
    def go(i: Int): Future[Unit] = {
      logger.debug(s"backfill() iteration $i")
      i should be < 100
      backfiller.backfill().flatMap {
        case HistoryBackfilling.Outcome.MoreWorkAvailableNow(_) => go(i + 1)
        case HistoryBackfilling.Outcome.MoreWorkAvailableLater => go(i + 1)
        case HistoryBackfilling.Outcome.BackfillingIsComplete => Future.unit
      }
    }
    go(1)
  }
}
