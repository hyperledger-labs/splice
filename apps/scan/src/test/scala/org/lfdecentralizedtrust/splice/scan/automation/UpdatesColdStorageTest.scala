package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.concurrent.ExecutionContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.s3.UpdatesColdStorageStore
import org.lfdecentralizedtrust.splice.store.{Limit, StoreTest, UpdateHistory}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import cats.implicits.*
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier}
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent

class UpdatesColdStorageTest extends StoreTest
  with HasExecutionContext {
    "UpdatesColdStorageTest" should {
      "do something" in {

        val acsSnapshotStore = mock[AcsSnapshotStore]
        val updateHistory = mock[UpdateHistory]
        val store = new UpdatesColdStorageStore(acsSnapshotStore, updateHistory, dsoParty = PartyId.tryFromProtoPrimitive("DSO::1234"), currentMigrationId = 0, loggerFactory)

        val now = CantonTimestamp.now()

        when(
          acsSnapshotStore.lookupSnapshotAtOrBefore(
            eqTo(0),
            eqTo(CantonTimestamp.MaxValue)
          )(any[TraceContext])
        ).thenReturn(Future.successful(Some(AcsSnapshotStore.AcsSnapshot(now, 0, 0, 0, 100, None, None))))

        val owner = providerParty(1)
        val amuletContract = amulet(owner, BigDecimal(10), 1L, BigDecimal(0.00001))
        val amuletContract2 = amulet(owner, BigDecimal(100), 5L, BigDecimal(0.00005))

        val createdEvents : Seq[SpliceCreatedEvent] = Seq(
          SpliceCreatedEvent(
            "id1",
            toCreatedEvent(amuletContract)
          ),
          SpliceCreatedEvent(
            "id2",
            toCreatedEvent(amuletContract2)
          )
        )

        when(
          acsSnapshotStore.queryAcsSnapshot(
            eqTo(0),
            eqTo(now),
            eqTo(None),
            eqTo(Limit.DefaultLimit),
            eqTo(Seq()),
            eqTo(Seq())
          )(any[TraceContext])
        ).thenReturn(Future.successful(AcsSnapshotStore.QueryAcsSnapshotResult(
          0,
          now,
          createdEventsInPage = createdEvents.toVector,
          afterToken = Some(5)
        )))


        for {
          lookup <- acsSnapshotStore.lookupSnapshotAtOrBefore(0, CantonTimestamp.MaxValue)
          snapshot <- lookup.traverse(l => acsSnapshotStore.queryAcsSnapshot(
            l.migrationId,
            l.snapshotRecordTime,
            None, // FIXME: pagination
            Limit.DefaultLimit,
            Seq(),
            Seq()))
          _ = snapshot match {
            case Some(s) => store.dumpAcsSnapshot(s)
            case _ => logger.debug("No snapshot found")
          }
        } yield succeed
      }
    }
}
