package org.lfdecentralizedtrust.splice.scan.store.bulk

import scala.concurrent.ExecutionContext
import com.digitalasset.canton.config.TopologyConfig.NotUsed
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.scan.admin.http.ProtobufJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import io.circe.syntax.*

case class S3Config()

case class BulkStorageConfig(
    dbReadChunkSize: Int,
    maxFileSize: Long,
)

class AcsSnapshotBulkStorage(
    val acsSnapshotStore: AcsSnapshotStore,
    val s3Config: S3Config,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  val bulkStorageConfigV1 = BulkStorageConfig(
    1000,
    (64 * 1024 * 1024).toLong,
  )

  def dumpAcsSnapshot(migrationId: Long, timestamp: CantonTimestamp): Future[Unit] = {
    val snapshotReadPointer: AtomicReference[Option[Long]] = new AtomicReference[Option[Long]](None)
    Source
      .repeat(NotUsed)
      .mapAsync(1) { _ =>
        for {
          snapshot <- acsSnapshotStore.queryAcsSnapshot(
            migrationId,
            snapshot = timestamp,
            snapshotReadPointer.get(),
            limit = HardLimit.tryCreate(bulkStorageConfigV1.dbReadChunkSize),
            Seq.empty,
            Seq.empty,
          )
          // FIXME: double check if the http API returned by javaToHttpCreatedEvent fits, or we need something slightly different

        } yield {
          // TODO: terminate the stream when done (when afterToken is none)
          snapshotReadPointer.set(snapshot.afterToken)
          val encoded = snapshot.createdEventsInPage.map(event =>
            ProtobufJsonScanHttpEncodings.javaToHttpCreatedEvent(event.eventId, event.event)
          )
          val contractsStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n")
          val contractsBytes = ByteString(contractsStr)
          logger.debug(
            s"Read ${encoded.length} contracts from ACS, to a bytestring of size ${contractsBytes.length} bytes"
          )
          contractsBytes
        }
      }
      .via(ZstdGroupedWeight(bulkStorageConfigV1.maxFileSize))
      .buffer(
        1,
        OverflowStrategy.backpressure,
      ) // Add a buffer so that the next object continues accumulating while we write the previous one
      .runWith(Sink.ignore)

  }.map(_ => ())
}
