package com.daml.network.store

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, TransactionTree, TreeEvent}
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.db.{AcsJdbcTypes, AcsTables, CNPostgresTest}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.scalatest.Assertion

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

class UpdateHistoryTest
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  import UpdateHistoryTest.*

  "UpdateHistory" should {

    "ingestion" should {

      "handle single create" in {
        val store = mkStore()
        for {
          _ <- initStore(store)
          _ <- create(domain1, cid1, offset1, party1, store)
          updates <- updates(store)
        } yield checkUpdates(
          updates,
          Seq(
            ExpectedCreate(cid1, domain1)
          ),
        )
      }

      "handle reassignments" in {
        implicit val store = mkStore()
        val c = appRewardCoupon(1, party1, contractId = cid1)
        for {
          _ <- initStore(store)
          _ <- domain1.create(c)
          _ <- domain1.unassign(c -> domain2, reassignmentId1, 1)
          _ <- domain2.assign(c -> domain1, reassignmentId1, 1)
          _ <- domain2.exercise(
            c,
            None,
            "Archive",
            com.daml.ledger.javaapi.data.Unit.getInstance(),
            com.daml.ledger.javaapi.data.Unit.getInstance(),
          )
          updates <- updates(store)
        } yield checkUpdates(
          updates,
          Seq(
            ExpectedCreate(cid1, domain1),
            ExpectedUnassign(cid1, domain1, domain2),
            ExpectedAssign(cid1, domain1, domain2),
            ExpectedExercise(cid1, domain2, "Archive"),
          ),
        )
      }

      // Note: we do not really want to support multiple UpdateHistory instances ingesting
      // data for the same party from the same participant. We still want the UpdateHistory
      // to behave correctly if this happens by accident, however.
      "handle many stores concurrently ingesting the same stream" in {
        // 10 stores, all ingesting the same stream
        val stores = (1 to 10).toList.map(_ => mkStore())

        // One update stream with 10 updates
        val updateStreamElements = (1 to 10).toList.map(i =>
          i -> TransactionTreeUpdate(
            mkCreateTx(
              validOffset(i),
              Seq(appRewardCoupon(1, party1, contractId = validContractId(i))),
              defaultEffectiveAt,
              Seq(party1),
              domain1,
              "workflowId",
            )
          )
        )

        // Retry once on failure
        def retryOnceAfterAShortDelay[T](f: => Future[T]): Future[T] = {
          f.recoverWith { case _: Throwable =>
            Future(Threading.sleep(100)).flatMap(_ => f)
          }
        }

        for {
          // Initialize all stores in parallel
          _ <- Future.traverse(stores)(s => initStore(s))
          // Process one update at a time
          _ <- withoutRepeatedIngestionWarning(
            MonadUtil.sequentialTraverse(updateStreamElements) { case (i, update) =>
              logger.info(s"Processing update $i")
              // Ingest the same update on all stores in parallel
              Future.traverse(stores)(s =>
                // The first concurrent update is expected to fail on all but one store
                // with a uniqueness violation error (assuming the updates are really concurrent).
                // At the latest on the next retry, all stores should succeed ingesting the update
                // by figuring out that the given offset was already ingested.
                // In practice, the ingestion service would crash and restart after a "short delay".
                retryOnceAfterAShortDelay(
                  s.ingestionSink
                    .ingestUpdate(
                      domain1,
                      update,
                    )
                )
              )
            },
            maxCount = 199, // 10 stores x 10 updates x up to 2 retries per update
          )
          // Query all stores in parallel
          updatesList <- Future.traverse(stores)(s => updates(s))
        } yield {
          // All stores should return all 10 updates
          updatesList.foreach(updates =>
            checkUpdates(
              updates,
              updateStreamElements.map(u => ExpectedCreate(validContractId(u._1), domain1)),
            )
          )

          succeed
        }
      }

      "two stores: different parties" in {
        val store1 = mkStore(party1, migration1, participant1)
        val store2 = mkStore(party2, migration1, participant1)

        for {
          _ <- initStore(store1)
          _ <- initStore(store2)
          _ <- create(domain1, cid1, offset1, party1, store1)
          _ <- create(domain1, cid2, offset2, party2, store2)
          updates1 <- updates(store1)
          updates2 <- updates(store2)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1)
            ),
          )
          checkUpdates(
            updates2,
            Seq(
              ExpectedCreate(cid2, domain1)
            ),
          )
        }
      }

      "two stores: different participant" in {
        val store1 = mkStore(party1, migration1, participant1)
        val store2 = mkStore(party1, migration1, participant2)

        for {
          _ <- initStore(store1)
          _ <- initStore(store2)
          // Note: same offset (offsets are participant-specific)
          _ <- create(domain1, cid1, offset1, party1, store1)
          _ <- create(domain1, cid2, offset1, party1, store2)
          updates1 <- updates(store1)
          updates2 <- updates(store2)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1)
            ),
          )
          checkUpdates(
            updates2,
            Seq(
              ExpectedCreate(cid2, domain1)
            ),
          )
        }
      }

      "two stores: different migration indices" in {
        val store1 = mkStore(party1, migration1, participant1)
        val store2 = mkStore(party1, migration2, participant1)

        for {
          _ <- initStore(store1)
          _ <- initStore(store2)
          // Note: same offset (offsets are not preserved across hard domain migrations)
          _ <- create(domain1, cid1, offset1, party1, store1)
          _ <- create(domain1, cid2, offset1, party1, store2)
          updates1 <- updates(store1)
          updates2 <- updates(store2)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1)
            ),
          )
          checkUpdates(
            updates2,
            Seq(
              ExpectedCreate(cid2, domain1)
            ),
          )
        }
      }

      "one store: different domains" in {
        val store1 = mkStore(party1, migration1, participant1)

        for {
          _ <- initStore(store1)
          // Note: the two contracts can share a record time (record times are not unique across domains)
          _ <- create(domain1, cid1, offset1, party1, store1)
          _ <- create(domain2, cid2, offset2, party1, store1)
          updates1 <- updates(store1)
        } yield {
          checkUpdates(
            updates1,
            Seq(
              ExpectedCreate(cid1, domain1),
              ExpectedCreate(cid2, domain2),
            ),
          )
        }
      }

    }
  }

  private def create(
      domain: DomainId,
      contractId: String,
      offset: String,
      party: PartyId,
      store: UpdateHistory,
  ) = {
    DomainSyntax(domain).create(
      c = appRewardCoupon(
        round = 0,
        provider = party,
        contractId = contractId,
      ),
      offset = offset,
      txEffectiveAt = defaultEffectiveAt,
      createdEventSignatories = Seq(party),
    )(
      store
    )
  }

  private def validOffset(i: Int) = {
    assert(i > 0)
    "%08d".format(i)
  }

  // Universal begin offset (strictly smaller than any offset used in this suite)
  private val beginOffset = "0".repeat(16)
  // Universal begin offset (strictly larger than any offset used in this suite)
  private val endOffset = "9".repeat(16)

  private def singleRootEvent(tree: TransactionTree): TreeEvent = {
    val rootEventIds = tree.getRootEventIds.asScala
    rootEventIds.length should be(1)
    val rootEventId = rootEventIds.headOption.value
    tree.getEventsById.get(rootEventId)
  }
  private def checkUpdates(
      actual: Seq[LedgerClient.GetTreeUpdatesResponse],
      expected: Seq[ExpectedUpdate],
  ): Assertion = {
    actual.length should be(expected.length)
    actual.zip(expected).foreach {
      case (GetTreeUpdatesResponse(TransactionTreeUpdate(tree), domain), expected) =>
        val rootEvent = singleRootEvent(tree)
        (rootEvent, expected) match {
          case (rootEvent: CreatedEvent, ExpectedCreate(cid, domainId)) =>
            rootEvent.getContractId should be(cid)
            domain should be(domainId)
          case (rootEvent: ExercisedEvent, ExpectedExercise(cid, domainId, choice)) =>
            rootEvent.getContractId should be(cid)
            rootEvent.getChoice should be(choice)
            domain should be(domainId)
          case _ => throw new RuntimeException("Unexpected event type")
        }
      case (GetTreeUpdatesResponse(ReassignmentUpdate(update), domain), expected) =>
        (update.event, expected) match {
          case (unassign: ReassignmentEvent.Unassign, expected: ExpectedUnassign) =>
            unassign.contractId.contractId should be(expected.cid)
            domain should be(expected.domainId)
            unassign.source should be(expected.domainId)
            unassign.target should be(expected.targetDomain)
          case (assign: ReassignmentEvent.Assign, expected: ExpectedAssign) =>
            assign.createdEvent.getContractId should be(expected.cid)
            assign.source should be(expected.sourceDomain)
            assign.target should be(expected.domainId)
          case _ => throw new RuntimeException("Unexpected reassignment type")
        }
      case _ => throw new RuntimeException("Unexpected update type")
    }
    succeed
  }

  private def updates(
      store: UpdateHistory,
      begin: String = beginOffset,
      end: String = endOffset,
  ): Future[Seq[LedgerClient.GetTreeUpdatesResponse]] = store
    .updateStream(begin, end)
    .toMat(Sink.seq)(Keep.right)
    .run()

  private def initStore(implicit store: UpdateHistory): Future[Unit] = {
    store.testIngestionSink.initialize().map(_ => ())
  }

  private def mkStore(
      updateStreamParty: PartyId = party1,
      domainMigrationId: Long = migration1,
      participantId: ParticipantId = participant1,
  ): UpdateHistory = {
    new UpdateHistory(
      storage,
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      participantId,
      updateStreamParty,
      loggerFactory,
    )
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()

  private val party1 = userParty(1)
  private val party2 = userParty(2)

  private val migration1 = 1L
  private val migration2 = 2L

  private val domain1: DomainId = DomainId.tryFromString("domain1::domain")
  private val domain2: DomainId = DomainId.tryFromString("domain2::domain")

  private val cid1 = validContractId(1)
  private val cid2 = validContractId(2)

  private val offset1 = validOffset(1)
  private val offset2 = validOffset(2)

  private val participant1 = ParticipantId("participant1")
  private val participant2 = ParticipantId("participant2")

  private val reassignmentId1 = "%08d".format(1)
}

object UpdateHistoryTest {
  sealed trait ExpectedUpdate extends Product with Serializable
  final case class ExpectedCreate(cid: String, domainId: DomainId) extends ExpectedUpdate
  final case class ExpectedExercise(cid: String, domainId: DomainId, choice: String)
      extends ExpectedUpdate

  final case class ExpectedAssign(cid: String, sourceDomain: DomainId, domainId: DomainId)
      extends ExpectedUpdate

  final case class ExpectedUnassign(cid: String, domainId: DomainId, targetDomain: DomainId)
      extends ExpectedUpdate
}
