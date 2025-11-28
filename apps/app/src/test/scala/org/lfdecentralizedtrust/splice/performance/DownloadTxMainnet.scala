package org.lfdecentralizedtrust.splice.performance

import cats.data.Chain
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import org.apache.pekko.actor.ActorSystem
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
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContextExecutor}

object DownloadTxMainnet extends App with NamedLogging {
  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  implicit val actorSystem: ActorSystem = ActorSystem()

  try {
    implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
    implicit val httpClient: HttpClient =
      HttpClient(HttpClient.HttpRequestParameters(NonNegativeDuration.ofSeconds(40L)), logger)
    val host = "https://scan.sv-2.global.canton.network.digitalasset.com"

    val migrationId = 3 // TODO: figure out how to configure this
    val client = http.ScanClient.httpClient(HttpClientBuilder().buildClient(), host)

    val now = Instant.now()
    val start = now.minus(1, ChronoUnit.HOURS)

    def query(at: Instant) = {
      println(s"Querying at $at")
      Await
        .result(
          client
            .getUpdateHistoryV2(
              UpdateHistoryRequestV2(
                after = Some(UpdateHistoryRequestAfter(migrationId.toLong, at.toString)),
                pageSize = 1000,
              )
            )
            .value,
          1.minute,
        )
        .getOrElse(throw new RuntimeException())
        .fold(identity, _ => throw new RuntimeException(), _ => throw new RuntimeException())
    }
    @tailrec
    def loop(at: Instant, acc: Chain[UpdateHistoryItemV2]): Chain[UpdateHistoryItemV2] = {
      val nextResponse = query(at)
      nextResponse.transactions.lastOption match {
        case Some(value) =>
          val recordTime = Instant.parse(value match {
            case members.UpdateHistoryTransactionV2(value) => value.recordTime
            case members.UpdateHistoryReassignment(value) => value.recordTime
          })
          if (recordTime.isAfter(now)) acc
          else
            loop(
              recordTime,
              acc ++ Chain.fromSeq(nextResponse.transactions),
            )
        case None =>
          acc
      }
    }

    val txsInDuration = loop(start, Chain.empty)
    val toWrite = UpdateHistoryResponseV2.encodeUpdateHistoryResponseV2(
      UpdateHistoryResponseV2(txsInDuration.toVector)
    )
    val path = Paths.get("update_history_response.json")
    Files.write(path, toWrite.noSpaces.getBytes(StandardCharsets.UTF_8))
  } finally {
    Await.result(actorSystem.terminate(), 10.seconds)
  }

}
