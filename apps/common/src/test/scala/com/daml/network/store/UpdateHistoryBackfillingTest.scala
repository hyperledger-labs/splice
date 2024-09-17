package com.daml.network.store

import com.daml.network.environment.ledger.api.LedgerClient

import scala.concurrent.Future

class UpdateHistoryBackfillingTest extends UpdateHistoryTestBase {

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
          _ <- storeA0.destinationHistory.markBackfillingComplete(0)
          _ <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
          _ <- create(domain2, validContractId(2), validOffset(2), party1, storeA0, time(2))
          _ <- create(domain1, validContractId(3), validOffset(3), party1, storeA0, time(3))
          // Store A ingests all of migration 1, with updates on only one domain
          _ <- initStore(storeA1)
          _ <- storeA1.destinationHistory.markBackfillingComplete(1)
          _ <- create(domain1, validContractId(4), validOffset(4), party1, storeA1, time(4))
          _ <- create(domain1, validContractId(5), validOffset(5), party1, storeA1, time(5))
          // Store A ingests one update in migration 2
          _ <- initStore(storeA2)
          _ <- storeA2.destinationHistory.markBackfillingComplete(2)
          _ <- create(domain2, validContractId(6), validOffset(6), party1, storeA2, time(6))
          // Store B joins it and now both stores ingest two updates on migration 2
          _ <- initStore(storeB2)
          _ <- createMulti(
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
          // Backfill
          backfiller = mkBackfilling(source = storeA2, destination = storeB2)
          _ <- backfillAll(backfiller)
          // Check that the updates are the same
          updatesA <- storeA2.getUpdates(None, PageLimit.tryCreate(1000))
          updatesB <- storeB2.getUpdates(None, PageLimit.tryCreate(1000))
          backfillingComplete <- storeB2.destinationHistory.isBackfillingComplete(2)
        } yield {
          backfillingComplete shouldBe true
          updatesA.map(_.update.update.updateId) should contain theSameElementsAs updatesB.map(
            _.update.update.updateId
          )
        }
      }
    }
  }

  private def mkBackfilling(source: UpdateHistory, destination: UpdateHistory) =
    new HistoryBackfilling[LedgerClient.GetTreeUpdatesResponse](
      destination.destinationHistory,
      source.sourceHistory,
      batchSize = 10,
      loggerFactory = loggerFactory,
    )

  private def backfillAll(
      backfiller: HistoryBackfilling[LedgerClient.GetTreeUpdatesResponse]
  ): Future[Unit] = {
    def go(i: Int): Future[Unit] = {
      logger.debug(s"backfill() iteration $i")
      backfiller.backfill().flatMap {
        case HistoryBackfilling.Outcome.MoreWorkAvailableNow => go(i + 1)
        case HistoryBackfilling.Outcome.MoreWorkAvailableLater => go(i + 1)
        case HistoryBackfilling.Outcome.BackfillingIsComplete => Future.unit
      }
    }
    go(1)
  }
}
