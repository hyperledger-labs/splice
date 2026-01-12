package org.lfdecentralizedtrust.splice.performance

import cats.data.Chain
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.{ActorSystem, Scheduler}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpClientBuilder
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.AcsRequest.RecordTimeMatch
import org.lfdecentralizedtrust.splice.http.v0.definitions.{AcsRequest, AcsResponse, CreatedEvent}
import org.lfdecentralizedtrust.splice.http.v0.scan as http

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.{Instant, ZoneOffset}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class DownloadScanAcsSnapshot(
    host: String,
    migrationId: Int,
    writePath: Path,
    snapshotTime: Instant,
)(implicit
    ec: ExecutionContext,
    as: ActorSystem,
    override val loggerFactory: NamedLoggerFactory,
    tc: TraceContext,
) extends Runnable
    with NamedLogging {

  def run(): Unit = {
    implicit val httpClient: HttpClient = {
      // request timeout slightly higher than the default 38s in Splice servers
      HttpClient(HttpClient.HttpRequestParameters(NonNegativeDuration.ofSeconds(40L)), logger)
    }
    implicit val scheduler: Scheduler = as.scheduler

    val client = http.ScanClient.httpClient(HttpClientBuilder().buildClient(), host)

    def query(after: Option[Long]): Future[AcsResponse] = {
      org.apache.pekko.pattern
        .retry(
          () => {
            logger.info(s"Querying at $after")
            client
              .getAcsSnapshotAt(
                AcsRequest(
                  migrationId.toLong,
                  snapshotTime.atOffset(ZoneOffset.UTC),
                  Some(RecordTimeMatch.AtOrBefore),
                  after,
                  pageSize = 1000,
                )
              )
              .value
              .map(
                _.fold(
                  error => {
                    val exception = new RuntimeException(error.toString)
                    logger.error(s"Failed to get updates at $after.", exception)
                    throw exception
                  },
                  _.fold(
                    identity,
                    error => throw new RuntimeException(error.error),
                    error => throw new RuntimeException(error.error),
                    error => throw new RuntimeException(error.error),
                  ),
                )
              )
          },
          attempts = 10,
          delay = 10.seconds,
        )
    }

    @tailrec
    def loop(after: Option[Long], acc: Chain[CreatedEvent]): Chain[CreatedEvent] = {
      val nextResponse = Await.result(query(after), atMost = 2.minutes)
      val newAcc = acc ++ Chain.fromSeq(nextResponse.createdEvents)
      nextResponse.nextPageToken match {
        case Some(newAfter) => loop(Some(newAfter), newAcc)
        case None => newAcc
      }
    }

    val acs = loop(None, Chain.empty)
    logger.info(
      s"Finished downloading ACS snapshot."
    )
    val toWrite = AcsResponse.encodeAcsResponse(
      AcsResponse(
        snapshotTime.atOffset(ZoneOffset.UTC),
        migrationId.toLong,
        acs.toVector,
        None,
      )
    )
    Files.write(writePath, toWrite.noSpaces.getBytes(StandardCharsets.UTF_8))
    logger.info(s"Successfully written ACS snapshot to $writePath.")
  }

}
