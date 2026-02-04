package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import org.lfdecentralizedtrust.splice.scan.store.ScanHistoryBackfilling
import org.lfdecentralizedtrust.splice.util.DomainRecordTimeRange
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BackfillingScanConnection
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

class ScanHistoryBackfillingTest extends UpdateHistoryStoreTestBase {

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
        allUpdatesA <- testData.sourceHistory.getAllUpdates(
          None,
          PageLimit.tryCreate(1000),
        )
        allUpdatesB <- testData.destinationHistory.getAllUpdates(
          None,
          PageLimit.tryCreate(1000),
        )
        stdUpdatesA <- testData.sourceHistory.getUpdatesWithoutImportUpdates(
          None,
          PageLimit.tryCreate(1000),
        )
        stdUpdatesB <- testData.destinationHistory.getUpdatesWithoutImportUpdates(
          None,
          PageLimit.tryCreate(1000),
        )
        importUpdatesA1 <- testData.sourceHistory.getImportUpdates(
          1,
          "",
          PageLimit.tryCreate(1000),
        )
        importUpdatesB1 <- testData.destinationHistory.getImportUpdates(
          1,
          "",
          PageLimit.tryCreate(1000),
        )
        importUpdatesA2 <- testData.sourceHistory.getImportUpdates(
          2,
          "",
          PageLimit.tryCreate(1000),
        )
        importUpdatesB2 <- testData.destinationHistory.getImportUpdates(
          2,
          "",
          PageLimit.tryCreate(1000),
        )
      } yield {
        backfillingTerminated shouldBe true
        backfillingComplete shouldBe true

        // `getAllUpdates()` returns updates as they are stored in the database, but import updates backfilling
        // rewrites them to be consistent across SVs. This transformation modifies update ids, so we can't use
        // regular equality. Moreover, the order of import updates will differ across stores.
        allUpdatesA should not contain theSameElementsAs(allUpdatesB)
        allUpdatesA.map(testUpdateIdentifier) should contain theSameElementsAs allUpdatesB
          .map(testUpdateIdentifier)

        // `getUpdatesWithoutImportUpdates()` returns updates as they are stored in the database.
        // Regular update backfilling may modify event ids, but in this test code it doesn't.
        stdUpdatesA should contain theSameElementsInOrderAs stdUpdatesB

        // `getImportUpdates()` returns updates in a form that is consistent across SVs
        importUpdatesA1 should contain theSameElementsInOrderAs importUpdatesB1
        importUpdatesA2 should contain theSameElementsInOrderAs importUpdatesB2
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
        updatesB1 <- testData.destinationHistory.getAllUpdates(
          None,
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
        allUpdatesA <- testData.sourceHistory.getAllUpdates(
          None,
          PageLimit.tryCreate(1000),
        )
        allUpdatesB2 <- testData.destinationHistory.getAllUpdates(
          None,
          PageLimit.tryCreate(1000),
        )
      } yield {
        backfillingTerminated1 shouldBe false
        migrationInfo0 shouldBe None
        backfillingTerminated2 shouldBe true
        backfillingComplete2 shouldBe true

        // See comments in previous test for why we can't use regular equality here
        val updateIdsA1 =
          allUpdatesA.filter(_.update.update.recordTime >= time(5)).map(testUpdateIdentifier)
        val updateIdsA2 = allUpdatesA.map(testUpdateIdentifier)
        val updateIdsB1 = updatesB1.map(testUpdateIdentifier)
        val updateIdsB2 = allUpdatesB2.map(testUpdateIdentifier)
        updateIdsB1 should contain theSameElementsAs updateIdsA1
        updateIdsB2 should contain theSameElementsAs updateIdsA2
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
      tx01 <- create(domain1, validContractId(1), validOffset(1), party1, storeA0, time(1))
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
      _ <- importUpdate(tx01, validOffset(1), storeA1)
      tx11 <- create(domain1, validContractId(4), validOffset(4), party1, storeA1, time(4))
      tx12 <- create(domain1, validContractId(5), validOffset(5), party1, storeA1, time(5))
      // Migration 2:
      // domain 1: .
      // domain 2: 6
      _ <- initStore(storeA2)
      _ <- importUpdate(tx01, validOffset(1), storeA2)
      _ <- importUpdate(tx11, validOffset(4), storeA2)
      _ <- importUpdate(tx12, validOffset(5), storeA2)
      _ <- create(domain2, validContractId(6), validOffset(6), party1, storeA2, time(6))
      // At this point, storeA2 joins and both continue with migration 2:
      // domain 1: 7 .
      // domain 2: . 8
      _ <- initStore(storeB2)
      tx22 <- createMulti(
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
        SynchronizerId.tryFromString(tx01.getSynchronizerId),
        tx01.getUpdateId,
        complete = true,
      )
      _ <- storeB2.initializeBackfilling(
        2,
        SynchronizerId.tryFromString(tx22.getSynchronizerId),
        tx22.getUpdateId,
        complete = false,
      )
    } yield TestData(storeA2, storeB2)
  }

  /** Returns a string that uniquely identifies the given update.
    * We use this to determine whether two updates are the same, without comparing the full content.
    * This approach is easier to read and debug than a custom Equality instance.
    */
  private def testUpdateIdentifier(tx: TreeUpdateWithMigrationId): String = {
    val update = tx.update.update match {
      case TransactionTreeUpdate(tree)
          if tree.getRecordTime == CantonTimestamp.MinValue.toInstant =>
        s"TransactionTreeImportUpdate(${tree.getEventsById.asScala.values.map(_.getContractId).mkString(", ")})"
      case TransactionTreeUpdate(tree) =>
        s"TransactionTreeUpdate(${tree.getUpdateId})"
      case ReassignmentUpdate(transfer) =>
        s"ReassignmentUpdate(${transfer.updateId})"
    }
    s"TreeUpdateWithMigrationId(GetTreeUpdatesResponse($update, ${tx.update.synchronizerId}), ${tx.migrationId})"
  }

  private def backfillAll(
      source: UpdateHistory,
      destination: UpdateHistory,
      excludeBefore: Map[SynchronizerId, CantonTimestamp],
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
        case HistoryBackfilling.Outcome.BackfillingIsComplete => goImportUpdates(1)
      }
    }
    def goImportUpdates(i: Int): Future[Boolean] = {
      logger.debug(s"backfillImportUpdates() iteration $i")
      backfiller.backfillImportUpdates().flatMap {
        case ImportUpdatesBackfilling.Outcome.MoreWorkAvailableNow(_) => goImportUpdates(i + 1)
        case ImportUpdatesBackfilling.Outcome.MoreWorkAvailableLater => Future.successful(false)
        case ImportUpdatesBackfilling.Outcome.BackfillingIsComplete => Future.successful(true)
      }
    }
    go(1)
  }

  /** Reads data from the given UpdateHistory, but throws away anything with a record time before the time given by excludeBefore.
    * This is to simulate a history that is still backfilling itself.
    */
  class TestBackfillingScanConnection(
      history: UpdateHistory,
      excludeBefore: Map[SynchronizerId, CantonTimestamp],
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
                .fold[Option[(SynchronizerId, DomainRecordTimeRange)]](None)(m =>
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
        synchronizerId: SynchronizerId,
        before: CantonTimestamp,
        atOrAfter: Option[CantonTimestamp],
        count: Int,
    )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] =
      history
        .getUpdatesBefore(
          migrationId,
          synchronizerId,
          before,
          atOrAfter,
          PageLimit.tryCreate(count),
        )(tc)
        .map(
          _.map(_.update).filter(u =>
            excludeBefore.get(synchronizerId).fold(false)(b => u.update.recordTime >= b)
          )
        )

    override def getImportUpdates(migrationId: Long, afterUpdateId: String, count: Int)(implicit
        tc: TraceContext
    ): Future[Seq[UpdateHistoryResponse]] = history
      .getImportUpdates(
        migrationId,
        afterUpdateId,
        PageLimit.tryCreate(count),
      )(tc)
      .map(
        _.map(_.update)
      )
  }
}
