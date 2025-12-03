package org.lfdecentralizedtrust.splice.scan.store

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.config.DbConfig.Postgres
import com.digitalasset.canton.config.{DbParametersConfig, DefaultProcessingTimeouts, StorageConfig}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.metrics.CommonMockMetrics
import com.digitalasset.canton.platform.store.DbSupport.DbConfig
import com.digitalasset.canton.resource.DbStorage.RetryConfig
import com.digitalasset.canton.resource.{DbStorage, DbStorageSingle, StorageSingleFactory}
import com.digitalasset.canton.store.db.DbStorageSetup.DbBasicConfig
import com.digitalasset.canton.store.db.{DbStorageIdempotency, DbStorageSetup, DbTest, PostgresTest}
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{OverflowStrategy, QueueOfferResult}
import org.scalatest.wordspec.AsyncWordSpec
import org.apache.pekko.stream.scaladsl.{Compression, Flow, Sink, Source, SourceQueueWithComplete}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, PageLimit, UpdateHistory}
import org.scalatest.concurrent.PatienceConfiguration
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import scala.concurrent.duration.*
import java.time.Instant
import java.util.concurrent.CompletionStage
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import io.circe.syntax._

// Setup:
// on CILR:
// cncluster debug_shell
//    apt install socat --yes
//    socat TCP-LISTEN:5432,reuseaddr,fork TCP:10.60.67.155:5432
// k port-forward splice-debug-itai 5432:5432

// For debugging, you can manually connect psql:
// PGPASSWORD=$(kubectl get secret --namespace sv-13 --output "jsonpath={.data.postgresPassword}" "cn-apps-pg-secrets" | base64 --decode) PGOPTIONS="--search-path=scan_sv_13,public" psql -h localhost -p 5432 --username=cnadmin --dbname scan_sv_13


class ScanBulkStoragePoc extends AsyncWordSpec with BaseTest with HasExecutionContext with PostgresTest {

  implicit val system: ActorSystem = ActorSystem("S3UploadPipeline")

  val liveSource: Source[ByteString, SourceQueueWithComplete[ByteString]] =
    Source.queue[ByteString](100, overflowStrategy = OverflowStrategy.backpressure)

  var latest = CantonTimestamp.MinValue


  def pollAndInjectData(queue: SourceQueueWithComplete[ByteString], updateHistory: UpdateHistory): Future[Unit] = {
    for {
      updates <- updateHistory.getUpdatesWithoutImportUpdates(Some((0, latest)), PageLimit.tryCreate(5))
      encoded = updates.map(update => ScanHttpEncodings.encodeUpdate(update, encoding = DamlValueEncoding.ProtobufJson, ScanHttpEncodings.V1))
      // TODO: replace toString with json encoding
      updatesStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n")
      _ <- queue.offer(ByteString(updatesStr))
    } yield {
      println("Added to the queue: ")
      println(updatesStr)
      latest = updates.last.update.update.recordTime
      println(s"Updated latest to: $latest")
    }
  }

  "scan" should {
    "dump stuff from postgres to s3" in {

      val dbConfig = mkDbConfig(DbBasicConfig("cnadmin", "d3RPvQB9nk5Z67X6", "scan_sv_13", "localhost", 5432, false, Some("scan_sv_13")))
      val storage = DbStorageSingle.tryCreate(
        dbConfig,
        new SimClock(CantonTimestamp.Epoch, loggerFactory),
        None,
        false,
        None,
        CommonMockMetrics.dbStorage,
        DefaultProcessingTimeouts.testing,
        loggerFactory,
        RetryConfig.failFast
      )
      val updateHistory = new UpdateHistory(
        storage,
        new DomainMigrationInfo(7, None),
        "DbScanStore",
        participantId = ParticipantId.tryFromProtoPrimitive("PAR::Digital-Asset-Eng-13::122069aae5c6f757c6cbd2be3c9e001c1c3d8a85eaa791b97a0b11b7fbff96e04ed7"),
        updateStreamParty = PartyId.tryFromProtoPrimitive("DSO::12209471e1a52edc2995ad347371597a5872f2704cb2cb4bb330a849e7309598259e"),
        backfillingRequired = BackfillingRequirement.BackfillingNotRequired,
        loggerFactory,
        enableissue12777Workaround = false,
        enableImportUpdateBackfill = false,
        HistoryMetrics(NoOpMetricsFactory, 7)
      )
      updateHistory.ingestionSink.initialize().futureValue


      val (queue, initStream) = liveSource
        .toMat(Sink.asPublisher(fanout = false))(org.apache.pekko.stream.scaladsl.Keep.both).run()

      system.scheduler.scheduleAtFixedRate(
        initialDelay = 1.second,
        interval = 10.seconds
      ) { () =>
        pollAndInjectData(queue, updateHistory).value
      }
      val streamCompletionFuture: Future[Done] = Source.fromPublisher(initStream)
        .map { uncompressedChunk =>
          println(s"Stream Flow: UNCOMPRESSED chunk size is ${uncompressedChunk.length} bytes.")
          uncompressedChunk
        }
        .via(Compression.gzip)
        .groupedWeighted((10 * 1024).toLong)((byteString: ByteString) => byteString.length.toLong)
        .map(chunks => {
          println(s"Grouped ${chunks.length} chunks")
          chunks.fold(ByteString.empty)(_ ++ _)
        })
        .runForeach(s => println(s"Stream consumed a compressed bytestring of size ${s.length}"))

      println("Application started. Waiting for stop condition...")
      streamCompletionFuture.futureValue(timeout = PatienceConfiguration.Timeout(1.minute))

      // Finally, terminate the ActorSystem
      println("Stream finished. Terminating system.")
      system.terminate().value

      succeed
    }
  }

  /** Hook for cleaning database before running next test. */
  override protected def cleanDb(storage: DbStorage)(implicit tc: TraceContext): FutureUnlessShutdown[?] =
    FutureUnlessShutdown.unit

}
