package org.lfdecentralizedtrust.splice.scan.store

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.config.DefaultProcessingTimeouts
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.metrics.CommonMockMetrics
import com.digitalasset.canton.resource.DbStorage.RetryConfig
import com.digitalasset.canton.resource.{DbStorage, DbStorageSingle}
import com.digitalasset.canton.store.db.DbStorageSetup.DbBasicConfig
import com.digitalasset.canton.store.db.PostgresTest
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{OverflowStrategy, QueueOfferResult}
import org.scalatest.wordspec.AsyncWordSpec
import org.apache.pekko.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, PageLimit, UpdateHistory}
import org.scalatest.concurrent.PatienceConfiguration
import com.github.luben.zstd.{ZstdDirectBufferCompressingStreamNoFinalizer}
import software.amazon.awssdk.services.s3.S3Client

import scala.concurrent.duration.*
import java.time.Duration

import java.time.Instant
import scala.concurrent.Future
import io.circe.syntax.*
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.PutObjectRequest

import java.net.URI
import java.nio.ByteBuffer

// Setup:
// on CILR:
// cncluster debug_shell
//    apt install socat --yes
//    socat TCP-LISTEN:5432,reuseaddr,fork TCP:10.60.67.155:5432
// k port-forward splice-debug-itai 5432:5432

// For debugging, you can manually connect psql:
// PGPASSWORD=$(kubectl get secret --namespace sv-13 --output "jsonpath={.data.postgresPassword}" "cn-apps-pg-secrets" | base64 --decode) PGOPTIONS="--search-path=scan_sv_13,public" psql -h localhost -p 5432 --username=cnadmin --dbname scan_sv_13


class ScanBulkStoragePoc extends AsyncWordSpec with BaseTest with HasExecutionContext with PostgresTest {

  val loggingConfigString =
    """
    pekko.loglevel = "DEBUG"
    // CRITICAL FIX: The logger class name must start with org.apache.pekko
    pekko.loggers = ["org.apache.pekko.event.slf4j.Slf4jLogger"]

    // Optional, but recommended for debugging HTTP traffic:
    pekko.http.client {
      log-request-response = on
      log-unencrypted-to-err-for-debug = true
      log-unencrypted-network-bytes = 2048
    }
  """

  val pekkoConfig: Config = ConfigFactory.parseString(loggingConfigString)
    .withFallback(ConfigFactory.load())


  var latest = CantonTimestamp.MinValue
  var total = 0

  val db_ip = "10.42.0.4"
  val db_pwd = sys.env("SPLICE_TEST_DB_CNADMIN_PWD")
  val participantId = ParticipantId.tryFromProtoPrimitive("PAR::Digital-Asset-Eng-13::122069aae5c6f757c6cbd2be3c9e001c1c3d8a85eaa791b97a0b11b7fbff96e04ed7")
  val partyId = PartyId.tryFromProtoPrimitive("DSO::12209471e1a52edc2995ad347371597a5872f2704cb2cb4bb330a849e7309598259e")
  val dbConfig = mkDbConfig(DbBasicConfig("cnadmin", db_pwd, "scan_sv_13", db_ip, 5432, false, Some("scan_sv_13")))
  val bucketName = "itai-test-updates-cold-storage"
  val accessKey = sys.env("COLD_STORAGE_ACCESS_KEY")
  val secret = sys.env("COLD_STORAGE_SECRET")
  val region = Region.AWS_GLOBAL // ignored by GCS
  val gcsEndpoint = URI.create("https://storage.googleapis.com")
  val credentials = AwsBasicCredentials.create(accessKey, secret)
  val zstdTmpBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024)
  val numUpdatesPerQuery = 1000
  val maxFileSize = 128 * 1024 * 1024

  implicit val system: ActorSystem = ActorSystem("S3UploadPipeline", pekkoConfig)

  val liveSource: Source[ByteString, SourceQueueWithComplete[ByteString]] =
    Source.queue[ByteString](100, overflowStrategy = OverflowStrategy.backpressure)

  val s3Client: S3Client = S3Client.builder()
    .endpointOverride(gcsEndpoint)
    .region(region)
    .credentialsProvider(StaticCredentialsProvider.create(credentials))
    .build()

  def injectUpdatesToStream(queue: SourceQueueWithComplete[ByteString], updateHistory: UpdateHistory): Future[Unit] = {
    logger.debug(s"Reading updates starting from $latest")
    for {
      updates <- updateHistory.getUpdatesWithoutImportUpdates(Some((0, latest)), PageLimit.tryCreate(numUpdatesPerQuery))
      encoded = updates.map(update => ScanHttpEncodings.encodeUpdate(update, encoding = DamlValueEncoding.ProtobufJson, ScanHttpEncodings.V1))
      updatesStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n")
      offerResult <- queue.offer(ByteString(updatesStr))
    } yield {
      latest = updates.last.update.update.recordTime
      logger.debug(s"Read ${updates.length} updates, up to: $latest")
      total = total + updates.length
      offerResult match {
        case QueueOfferResult.Enqueued =>
        case QueueOfferResult.Dropped =>
          throw new RuntimeException("Offer failed: Element Dropped due to full buffer.")
        case QueueOfferResult.QueueClosed =>
          throw new IllegalStateException("Offer failed: Queue is closed.")
        case QueueOfferResult.Failure(ex) =>
          throw new RuntimeException(s"Offer failed: Stream failed with exception: ${ex.getMessage}", ex)
      }
    }
  }

  var compressingStream = new ZstdDirectBufferCompressingStreamNoFinalizer(zstdTmpBuffer, 3)

  // A fairly naive implementation of a Pekko operator that zstd-compresses a ByteString
  // Currently assumes that it has a tmpBuffer large enough to compress tne entire input
  // ByteString, i.e. that its input is cut into small enough chunks.
  // Note also that it flushes and reuses the buffer on every call. I believe (though not 100% sure)
  // that this implies that to achieve deterministic output, the input must be chunked
  // deterministically too.
  def zstd(input: ByteString): ByteString = {
    val inputBB = ByteBuffer.allocateDirect(input.size)
    inputBB.put(input.toArrayUnsafe())
    inputBB.flip()
    compressingStream.compress(inputBB)
    compressingStream.flush()
    zstdTmpBuffer.flip()
    val result = ByteString.fromByteBuffer(zstdTmpBuffer)
    zstdTmpBuffer.clear()
    result
  }

  // Must call this once and append its output to the zstd ByteString for the generated
  // zstd ByteString to be valid zstd.
  def zstdFinish(): ByteString = {
    compressingStream.close()
    zstdTmpBuffer.flip()
    val result = ByteString.fromByteBuffer(zstdTmpBuffer)
    zstdTmpBuffer.clear()
    compressingStream = new ZstdDirectBufferCompressingStreamNoFinalizer(zstdTmpBuffer, 3)
    result
  }

  "scan" should {
    "dump updates from postgres to s3" in {

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
        participantId,
        updateStreamParty = partyId,
        backfillingRequired = BackfillingRequirement.BackfillingNotRequired,
        loggerFactory,
        enableissue12777Workaround = false,
        enableImportUpdateBackfill = false,
        HistoryMetrics(NoOpMetricsFactory, 7)
      )
      updateHistory.ingestionSink.initialize().futureValue


      val (queue, initStream) = liveSource
        .toMat(Sink.asPublisher(fanout = false))(org.apache.pekko.stream.scaladsl.Keep.both).run()

      var idx = 0

      system.scheduler.scheduleAtFixedRate(
        initialDelay = 1.second,
        interval = 1.seconds
      ) { () =>
        injectUpdatesToStream(queue, updateHistory).futureValue
      }
      val streamCompletionFuture = Source.fromPublisher(initStream)
        .map { uncompressedChunk =>
          logger.debug(s"uncompressed chunk size is ${uncompressedChunk.length} bytes.")
          uncompressedChunk
        }
        .map(zstd(_))
        .map { compressedChunk =>
          logger.debug(s"compressed chunk size is ${compressedChunk.length} bytes.")
          compressedChunk
        }
        .groupedWeighted((maxFileSize).toLong)((byteString: ByteString) => byteString.length.toLong)
        .map(chunks => chunks.fold(ByteString.empty)(_ ++ _))
        .map(_ ++ zstdFinish())
        .map { finalChunk =>
          logger.debug(s"Next chunk to be written is ready, it has ${finalChunk.length} bytes")
          finalChunk
        }
        .buffer(1, OverflowStrategy.backpressure)
        .mapAsync(1) { data =>
          // Pekko S3 sink breaks on GCS (seemingly because GCS returns xml responses with text/html type headers,
          // which breaks the unmarshaller in pekko s3), so we implement our own S3 upload here. For now, it's a
          // naive putObject, but we might want to consider making it a multipart upload instead.
          idx = idx + 1
          logger.debug(s"Writing updates_$idx (${data.length} bytes)")

          val objectKey = s"updates_$idx.zstd"
          val putObj: PutObjectRequest = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(objectKey)
            .build();
          Future {
            val start = Instant.now()
            logger.debug(s"Writing object to gs://$bucketName/$objectKey using S3 client.")
            s3Client.putObject(
              putObj,
              RequestBody.fromBytes(data.toArrayUnsafe())
            )
            val end = Instant.now()
            logger.debug(s"Successfully wrote object to gs://$bucketName/$objectKey using S3 client in ${Duration.between(start, end)}")
          }



//          Source.single(data).runWith(FileIO.toPath(Paths.get(s"/home/itai/Downloads/updates_$idx.zstd")))
          // Calling multiPartUploadWithHeaders, and not multiPartUpload which adds cannedACL headers which GCS does not support
//          Source.single(data).runWith(S3.multipartUploadWithHeaders("itai-test-updates-cold-storage", s"updates_${idx}.zstd").withAttributes(S3Attributes.settings(gcsS3Settings)))
        }
        .take(3) // Terminate after writing 3 files
        .runWith(Sink.ignore)

      streamCompletionFuture.futureValue(timeout = PatienceConfiguration.Timeout(15.minute))
      system.terminate().futureValue
//      succeed

      // Fail the test so that logs are uploaded
      fail()
    }
  }

  /** Hook for cleaning database before running next test. */
  override protected def cleanDb(storage: DbStorage)(implicit tc: TraceContext): FutureUnlessShutdown[?] =
    FutureUnlessShutdown.unit

}
