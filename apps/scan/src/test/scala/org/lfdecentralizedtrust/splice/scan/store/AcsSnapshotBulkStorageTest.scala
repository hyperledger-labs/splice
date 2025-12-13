package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotResult
import org.lfdecentralizedtrust.splice.scan.store.bulk.{AcsSnapshotBulkStorage, AcsSnapshotSource, S3Config}
import org.lfdecentralizedtrust.splice.store.{Limit, StoreTest}
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import org.lfdecentralizedtrust.splice.util.PackageQualifiedName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region

import java.net.URI
import scala.concurrent.Future

class AcsSnapshotBulkStorageTest extends StoreTest with HasExecutionContext with HasActorSystem{
  "AcsSnapshotSourceTest" should {
      "work" in {
        val store = mockAcsSnapshotStore()
        val timestamp = CantonTimestamp.now()
        val s3Config = S3Config(
          URI.create("http://localhost:9090"),
          "bucket",
          Region.US_EAST_1,
          AwsBasicCredentials.create("mock_id", "mock_key")
        )
        for {
          _ <- new AcsSnapshotBulkStorage(store, s3Config, loggerFactory).dumpAcsSnapshot(0, timestamp)
        } yield {succeed}
      }
  }

  def mockAcsSnapshotStore(): AcsSnapshotStore = {
    val store = mock[AcsSnapshotStore]
    val partyId = mkPartyId("alice")
    when(store.queryAcsSnapshot(
      anyLong,
      any[CantonTimestamp],
      any[Option[Long]],
      any[Limit],
      any[Seq[PartyId]],
      any[Seq[PackageQualifiedName]])(any[TraceContext])
    ).thenAnswer { (migration: Long, timestamp: CantonTimestamp, after: Option[Long], limit: Limit, _: Seq[PartyId], _: Seq[PackageQualifiedName]) =>
      Future {
        val numElems = if (after.getOrElse(0L) < 48000) {limit.limit} else {limit.limit / 2}
        val result = QueryAcsSnapshotResult(
          migration,
          timestamp,
          Vector.range(0, numElems).map(i => {
            val idx = i + after.getOrElse(0L)
            val amt = amulet(partyId, BigDecimal(idx), 0L, BigDecimal(0.1))
            SpliceCreatedEvent(s"event_id_$idx", toCreatedEvent(amt))
          }),
          if (numElems < limit.limit) None else {Some(after.getOrElse(0L) + numElems)}
        )
        println(s"mockAcsSnapshotStore returning ${result.createdEventsInPage.size} elements, with next token: ${result.afterToken}")
        result
      }
    }
    store
  }
}
