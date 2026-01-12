package org.lfdecentralizedtrust.splice.performance

import cats.data.Chain
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.{ActorSystem, Scheduler}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpClientBuilder
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  UpdateHistoryItemV2,
  UpdateHistoryRequestAfter,
  UpdateHistoryRequestV2,
  UpdateHistoryResponseV2,
}
import org.lfdecentralizedtrust.splice.http.v0.scan as http

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

class DownloadScanUpdates(
    host: String,
    migrationId: Int,
    writePath: Path,
    startAt: Instant,
    duration: Duration,
)(implicit
    ec: ExecutionContext,
    as: ActorSystem,
    override val loggerFactory: NamedLoggerFactory,
    tc: TraceContext,
) extends Runnable
    with NamedLogging {

  val until: Instant = startAt.plusNanos(duration.toNanos)

  def run(): Unit = {
    implicit val httpClient: HttpClient = {
      // request timeout slightly higher than the default 38s in Splice servers
      HttpClient(HttpClient.HttpRequestParameters(NonNegativeDuration.ofSeconds(40L)), logger)
    }
    implicit val scheduler: Scheduler = as.scheduler

    val client = http.ScanClient.httpClient(HttpClientBuilder().buildClient(), host)

    def query(at: Instant): Future[UpdateHistoryResponseV2] = {
      org.apache.pekko.pattern
        .retry(
          () => {
            logger.info(s"Querying at $at")
            client
              .getUpdateHistoryV2(
                UpdateHistoryRequestV2(
                  after = Some(UpdateHistoryRequestAfter(migrationId.toLong, at.toString)),
                  pageSize = 1000,
                )
              )
              .value
              .map(
                _.fold(
                  error => {
                    val exception = new RuntimeException(error.toString)
                    logger.error(s"Failed to get updates at $at.", exception)
                    throw exception
                  },
                  _.fold(
                    identity,
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
    def loop(at: Instant, acc: Chain[UpdateHistoryItemV2]): Chain[UpdateHistoryItemV2] = {
      val nextResponse = Await.result(query(at), atMost = 2.minutes)
      val (pastTime, toInclude) = nextResponse.transactions.partition(tx => {
        val recordTime = getRecordTime(tx)
        recordTime.isAfter(until)
      })
      val newAcc = acc ++ Chain.fromSeq(toInclude)
      if (pastTime.isEmpty)
        loop(
          getRecordTime(
            toInclude.lastOption.getOrElse(
              throw new IllegalStateException(
                "The updates are in the past, so there should never be an empty list!"
              )
            )
          ),
          newAcc,
        )
      else newAcc
    }

    val txsInDuration = loop(startAt, Chain.empty)
    logger.info(
      s"Finished downloading updates. Last update is ${txsInDuration.lastOption}, total updates: ${txsInDuration.size}."
    )
    val toWrite = UpdateHistoryResponseV2.encodeUpdateHistoryResponseV2(
      UpdateHistoryResponseV2(txsInDuration.toVector)
    )
    Files.write(writePath, toWrite.noSpaces.getBytes(StandardCharsets.UTF_8))
    logger.info(s"Successfully written updates to $writePath.")
  }

  private def getRecordTime(tx: UpdateHistoryItemV2): Instant = {
    Instant.parse(tx match {
      case members.UpdateHistoryTransactionV2(value) => value.recordTime
      case members.UpdateHistoryReassignment(value) => value.recordTime
    })
  }

}
