package org.lfdecentralizedtrust.splice.scan.store.s3

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.core.sync.RequestBody
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotResult

import java.net.URI
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.concurrent.ExecutionContext

class UpdatesColdStorageStore(
                              val acsSnapshotStore: AcsSnapshotStore,
                              val updateHistory: UpdateHistory,
                              dsoParty: PartyId,
                              val currentMigrationId: Long,
                              override protected val loggerFactory: NamedLoggerFactory,

                            ) extends NamedLogging {

  val bucketName = "itai-test-updates-cold-storage"
  val accessKey = sys.env("COLD_STORAGE_ACCESS_KEY")
  val secret = sys.env("COLD_STORAGE_SECRET")
  val region = Region.AWS_GLOBAL // ignored by GCS
  val tmpfilePath = "/tmp"
  val tmpfileName = f"$tmpfilePath/out.cbor"


  def dumpAcsSnapshot(acsSnapshot: QueryAcsSnapshotResult)(implicit ec: ExecutionContext, tc: TraceContext): Unit = {

    val folder = f"migration_${acsSnapshot.migrationId}_time_${acsSnapshot.snapshotRecordTime.toInstant.toString}"
    val objectName = "ACS.cbor"
    val objectKey = f"$folder/$objectName"

    val cborFactory = new CBORFactory()
    val cborMapper = new ObjectMapper(cborFactory)
    cborMapper.registerModule(DefaultScalaModule)
    cborMapper.registerModule(new Jdk8Module())  // for Java Optional
    cborMapper.registerModule(new JavaTimeModule()) // for java.time.Instant

    val cborBytes: Array[Byte] = cborMapper.writeValueAsBytes(acsSnapshot)

    val path = Paths.get(tmpfileName)
    val tmpfile = Files.write(path, cborBytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    logger.debug(f"Saved cbor object with ${cborBytes.length} bytes to tmp file $tmpfileName")

    val gcsEndpoint = URI.create("https://storage.googleapis.com")
    val credentials = AwsBasicCredentials.create(accessKey, secret)
    val s3Client: S3Client = S3Client.builder()
      .endpointOverride(gcsEndpoint)
      .region(region)
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .build()
    val putObj: PutObjectRequest = PutObjectRequest.builder()
      .bucket(bucketName)
      .key(objectKey)
      .build();
    s3Client.putObject(
      putObj,
      RequestBody.fromFile(tmpfile)
    )
    logger.debug(s"Successfully wrote object to gs://$bucketName/$objectKey using S3 client.")
  }
}

object UpdatesColdStorageStore {
  def apply(
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    dsoParty: PartyId,
    currentMigrationId: Long,
    loggerFactory: NamedLoggerFactory,
  ): UpdatesColdStorageStore = {
    new UpdatesColdStorageStore(acsSnapshotStore, updateHistory, dsoParty, currentMigrationId, loggerFactory)
  }
}
