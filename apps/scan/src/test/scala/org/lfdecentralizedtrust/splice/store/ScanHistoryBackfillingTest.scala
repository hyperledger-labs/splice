package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient
import org.lfdecentralizedtrust.splice.scan.store.ScanHistoryBackfilling
import org.lfdecentralizedtrust.splice.util.DomainRecordTimeRange
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BackfillingScanConnection
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

class ScanHistoryBackfillingTest extends UpdateHistoryTestBase {

  "ScanHistoryBackfilling" should {
    "backfill from one complete history" in {
      for {
        testData <- setup()
        // Backfill
        backfillingTerminated <- backfillAll(
          testData.sourceHistory,
          testData.destinationHistory,
          Map(domain1 -> time(0), domain2 -> time(0)),
        )
        backfillingComplete <- testData.destinationHistory.sourceHistory
          .migrationInfo(0)
          .map(_.value.complete)
        // Check that the updates are the same
        updatesA <- testData.sourceHistory.getUpdates(
          None,
          includeImportUpdates = true,
          PageLimit.tryCreate(1000),
        )
        updatesB <- testData.destinationHistory.getUpdates(
          None,
          includeImportUpdates = true,
          PageLimit.tryCreate(1000),
        )
      } yield {
        backfillingTerminated shouldBe true
        backfillingComplete shouldBe true
        updatesA.map(_.update.update.updateId) should contain theSameElementsInOrderAs updatesB.map(
          _.update.update.updateId
        )
      }
    }

    "backfill from one incomplete history" in {
      for {
        testData <- setup()

        // Backfill part 1 - at this point, the destination history has only replicated up to record time 5
        backfillingTerminated1 <- backfillAll(
          testData.sourceHistory,
          testData.destinationHistory,
          Map(domain1 -> time(5), domain2 -> time(5)),
        )
        migrationInfo0 <- testData.destinationHistory.sourceHistory
          .migrationInfo(0)
        updatesB1 <- testData.destinationHistory.getUpdates(
          None,
          includeImportUpdates = true,
          PageLimit.tryCreate(1000),
        )

        // Backfill part 2 - now the destination history has replicated everything
        backfillingTerminated2 <- backfillAll(
          testData.sourceHistory,
          testData.destinationHistory,
          Map(domain1 -> time(0), domain2 -> time(0)),
        )
        backfillingComplete2 <- testData.destinationHistory.sourceHistory
          .migrationInfo(0)
          .map(_.value.complete)

        // Check that the updates are the same
        updatesA <- testData.sourceHistory.getUpdates(
          None,
          includeImportUpdates = true,
          PageLimit.tryCreate(1000),
        )
        updatesB2 <- testData.destinationHistory.getUpdates(
          None,
          includeImportUpdates = true,
          PageLimit.tryCreate(1000),
        )
      } yield {
        backfillingTerminated1 shouldBe false
        migrationInfo0 shouldBe None
        backfillingTerminated2 shouldBe true
        backfillingComplete2 shouldBe true
        val updateIdsA1 =
          updatesA.filter(_.update.update.recordTime >= time(5)).map(_.update.update.updateId)
        val updateIdsA2 = updatesA.map(_.update.update.updateId)
        val updateIdsB1 = updatesB1.map(_.update.update.updateId)
        val updateIdsB2 = updatesB2.map(_.update.update.updateId)
        updateIdsB1 should contain theSameElementsInOrderAs updateIdsA1
        updateIdsB2 should contain theSameElementsInOrderAs updateIdsA2
      }
    }
  }

  /** sourceHistory contains all updates since ledger begin,
    * destinationHistory joined later and only contains some updates.
    */
  case class TestData(
      sourceHistory: UpdateHistory,
      destinationHistory: UpdateHistory,
  )

  private def setup(): Future[TestData] = {
    val storeA0 = mkStore(domainMigrationId = 0, participantId = participant1)
    val storeA1 = mkStore(domainMigrationId = 1, participantId = participant1)
    val storeA2 = mkStore(domainMigrationId = 2, participantId = participant1)
    val storeB2 = mkStore(domainMigrationId = 2, participantId = participant2)

    for {
      // Migration 0:
      // domain 1: 1 . 3
      // domain 2: . 2 .
      _ <- initStore(storeA0)
      tx1 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
      _ <- assign(
        domain2,
        domain1,
        validContractId(1),
        validOffset(2),
        party1,
        0,
        "rid1",
        storeA0,
        time(2),
      )
      _ <- unassign(
        domain1,
        domain2,
        validContractId(1),
        validOffset(3),
        party1,
        0,
        "rid1",
        storeA0,
        time(3),
      )
      // Migration 1:
      // domain 1: 4 5
      // domain 2: . .
      _ <- initStore(storeA1)
      _ <- create(domain1, validContractId(4), validOffset(4), party1, storeA1, time(4))
      _ <- create(domain1, validContractId(5), validOffset(5), party1, storeA1, time(5))
      // Migration 2:
      // domain 1: .
      // domain 2: 6
      _ <- initStore(storeA2)
      _ <- create(domain2, validContractId(6), validOffset(6), party1, storeA2, time(6))
      // At this point, storeA2 joins and both continue with migration 2:
      // domain 1: 7 .
      // domain 2: . 8
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
      _ <- storeA0.initializeBackfilling(
        0,
        DomainId.tryFromString(tx1.getDomainId),
        tx1.getUpdateId,
        complete = true,
      )
      _ <- storeB2.initializeBackfilling(
        2,
        DomainId.tryFromString(tx2.getDomainId),
        tx2.getUpdateId,
        complete = false,
      )
    } yield TestData(storeA2, storeB2)
  }

  private def backfillAll(
      source: UpdateHistory,
      destination: UpdateHistory,
      excludeBefore: Map[DomainId, CantonTimestamp],
  ): Future[Boolean] = {
    val connection = new TestBackfillingScanConnection(
      source,
      excludeBefore,
      logger,
    )
    val backfiller = new ScanHistoryBackfilling(
      connection = connection,
      destinationHistory = destination.destinationHistory,
      currentMigrationId = destination.domainMigrationInfo.currentMigrationId,
      batchSize = 1,
      loggerFactory = loggerFactory,
    )
    def go(i: Int): Future[Boolean] = {
      logger.debug(s"backfill() iteration $i")
      backfiller.backfill().flatMap {
        case HistoryBackfilling.Outcome.MoreWorkAvailableNow(_) => go(i + 1)
        case HistoryBackfilling.Outcome.MoreWorkAvailableLater => Future.successful(false)
        case HistoryBackfilling.Outcome.BackfillingIsComplete => Future.successful(true)
      }
    }
    go(1)
  }

  /** Reads data from the given UpdateHistory, but throws away anything with a record time before the time given by excludeBefore.
    * This is to simulate a history that is still backfilling itself.
    */
  class TestBackfillingScanConnection(
      history: UpdateHistory,
      excludeBefore: Map[DomainId, CantonTimestamp],
      override val logger: TracedLogger,
  ) extends BackfillingScanConnection {
    override def timeouts = com.digitalasset.canton.config.DefaultProcessingTimeouts.testing
    override def closeAsync(): Seq[com.digitalasset.canton.lifecycle.AsyncOrSyncCloseable] =
      Seq.empty

    override def getMigrationInfo(
        migrationId: Long
    )(implicit tc: TraceContext): Future[Option[SourceMigrationInfo]] =
      for {
        original <- history.sourceHistory.migrationInfo(migrationId)(tc)
        filteredRange = original.map(
          _.recordTimeRange.toList
            .flatMap { case (k, v) =>
              excludeBefore
                .get(k)
                .fold[Option[(DomainId, DomainRecordTimeRange)]](None)(m =>
                  if (v.max < m)
                    None
                  else
                    Some(k -> DomainRecordTimeRange(v.min.max(m), v.max))
                )
            }
            .toMap
        )
      } yield {
        if (original.map(_.recordTimeRange) != filteredRange) {
          logger.debug(
            s"Modifying record time range for migration $migrationId from ${original.value.recordTimeRange} to ${filteredRange.value}"
          )(tc)
          original.map(
            _.copy(
              recordTimeRange = filteredRange.value,
              complete = false,
            )
          )
        } else {
          original
        }
      }

    override def getUpdatesBefore(
        migrationId: Long,
        domainId: DomainId,
        before: CantonTimestamp,
        atOrAfter: Option[CantonTimestamp],
        count: Int,
    )(implicit tc: TraceContext): Future[Seq[LedgerClient.GetTreeUpdatesResponse]] =
      history
        .getUpdatesBefore(
          migrationId,
          domainId,
          before,
          atOrAfter,
          PageLimit.tryCreate(count),
        )(tc)
        .map(
          _.map(_.update).filter(u =>
            excludeBefore.get(domainId).fold(false)(b => u.update.recordTime >= b)
          )
        )
  }
}
