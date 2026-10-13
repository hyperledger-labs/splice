// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.implicits.*
import com.daml.metrics.api.MetricHandle.Timer.TimerHandle
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.logging.{ErrorLoggingContext, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.LoggerUtil
import com.digitalasset.canton.util.retry.{ErrorKind, ExceptionRetryPolicy}
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes, Uri}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.environment.{BaseAppConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.environment.RetryProvider.QuietNonRetryableException
import org.lfdecentralizedtrust.splice.metrics.ScanConnectionMetrics
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftCallExecutor.DataAvailabilityResponse.{
  Available,
  Never,
  NotYet,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.{
  BftCallConfig,
  ScanConnections,
}
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.slf4j.event.Level

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Random, Success, Try}
import scala.jdk.CollectionConverters.*

object BftCallExecutor {

  sealed trait DataAvailabilityResponse
  object DataAvailabilityResponse {
    case object Available extends DataAvailabilityResponse
    case object NotYet extends DataAvailabilityResponse
    case object Never extends DataAvailabilityResponse
  }

  final class NoScanWillHaveData(msg: String) extends QuietNonRetryableException(msg)

  /*
  A two-phase approach for endpoints for which eventual consistency is expected.
  Accepts two functions: `hasData` which queries each scan for whether the data required is available already,
  and `getData`, which gets the actual data for which bft equality is tested.
  This function first calls `hasData` on all provided Scan connections, to find `callConfig.requestsToDo`
  scans that have the required data already, and then `getData` on those (and then the standard bft comparison among them).

  The returned future fails with `ServiceUnavailable (503)` if not enough scans have the data, but some have responded to `hasData` with `NotYet`,
  with `NoScanWillHaveData` if all scans responded to `hasData` with `Never`, and with `BadGateway (502)` if the scans that have the data disagree.
   */
  def bftCallForEventualConsistencyEndpoints[T](
      connections: ScanConnections,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
      retryProvider: RetryProvider,
      logger: TracedLogger,
      hasData: SingleScanConnection => Future[DataAvailabilityResponse],
      getData: SingleScanConnection => Future[T],
      endpoint: String,
      callConfig: BftCallConfig,
      consensusFailureLogLevel: Level = Level.WARN,
      disagreementLogLevel: Level =
        Level.WARN, // In eventual consistency endpoints, we don't typically expect disagreements once data is available
      notEnoughScansLogLevel: Level = Level.WARN,
      shortenResponsesForLog: T => Any = identity[T],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      loggingContext: com.digitalasset.canton.logging.ErrorLoggingContext,
  ): Future[(T, List[Uri])] = {
    implicit val mc: MetricsContext = MetricsContext("request" -> endpoint)
    if (!callConfig.enoughAvailableScans) {
      handleNotEnoughScans(connections, callConfig, notEnoughScansLogLevel, connectionMetrics)
    } else {
      def startTimer(): Option[TimerHandle] =
        connectionMetrics.map(_.bftReadLatency.startAsync())
      def stopTimer(t: Option[TimerHandle]): Unit = t.foreach(_.stop())

      val timer = startTimer()

      findScansWithAvailableData(
        connections.open,
        logger,
        hasData,
        callConfig.requestsToDo,
        connectionMetrics,
        unreachable = connections.failed,
      ).flatMap { scansWithData =>
        {
          executeCallWithRetries(
            connectionMetrics,
            retryProvider,
            logger,
            getData,
            disagreementLogLevel,
            shortenResponsesForLog,
            requestFrom = scansWithData,
            // If we accepted fewer scans than the targetSuccess, that's because all others will never have the data.
            // This is common when bootstrapping the network, when multiple scans join after genesis, and all are trying
            // to backfill from the same source.
            // In this case, we're reducing the targetSuccess to the number of scans that actually have the data.
            // That means that we're accepting fewer scans than `targetSuccess` providing data for this case, but do
            // not tolerate any disagreement among those scans that actually have the data.
            nTargetSuccess = math.min(scansWithData.size, callConfig.targetSuccess),
            consensusFailureLogLevel,
          )
        }
      }.andThen(_ => stopTimer(timer))
    }
  }

  def bftCallWithScanUris[T](
      connections: ScanConnections,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
      retryProvider: RetryProvider,
      logger: TracedLogger,
      call: SingleScanConnection => Future[T],
      endpoint: String,
      callConfig: BftCallConfig,
      consensusFailureLogLevel: Level = Level.WARN,
      disagreementLogLevel: Level = Level.INFO,
      notEnoughScansLogLevel: Level,
      shortenResponsesForLog: T => Any = identity[T],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      loggingContext: com.digitalasset.canton.logging.ErrorLoggingContext,
  ): Future[(T, List[Uri])] = {
    implicit val mc: MetricsContext = MetricsContext("request" -> endpoint)

    def startTimer(): Option[TimerHandle] =
      connectionMetrics.map(_.bftReadLatency.startAsync())
    def stopTimer(t: Option[TimerHandle]): Unit = t.foreach(_.stop())

    if (!callConfig.enoughAvailableScans) {
      handleNotEnoughScans(connections, callConfig, notEnoughScansLogLevel, connectionMetrics)
    } else {
      val timer = startTimer()
      val nTargetSuccess = callConfig.targetSuccess

      executeCallWithRetries(
        connectionMetrics,
        retryProvider,
        logger,
        call,
        disagreementLogLevel,
        shortenResponsesForLog,
        Random.shuffle(callConfig.connections).take(callConfig.requestsToDo),
        nTargetSuccess,
        consensusFailureLogLevel,
      )
        .andThen(_ => stopTimer(timer))
    }
  }

  private def executeCallWithRetries[T, C <: HasUrl](
      connectionMetrics: Option[ScanConnectionMetrics],
      retryProvider: RetryProvider,
      logger: TracedLogger,
      call: C => Future[T],
      disagreementLogLevel: Level,
      shortenResponsesForLog: T => Any,
      // `requestFrom` is passed as a function so that we can e.g. reshuffle the list of scans to call on each retry
      requestFrom: => Seq[C],
      nTargetSuccess: Int,
      consensusFailureLogLevel: Level,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      loggingContext: com.digitalasset.canton.logging.ErrorLoggingContext,
      mc: MetricsContext,
  ): Future[(T, List[Uri])] = {
    retryProvider
      .retryForClientCalls(
        "bft_call",
        s"Bft call with $nTargetSuccess out of ${requestFrom.size} matching responses",
        executeCall(
          call,
          requestFrom,
          nTargetSuccess,
          logger,
          shortenResponsesForLog,
          disagreementLogLevel,
          connectionMetrics,
        ),
        logger,
        (_: String) => ConsensusNotReachedRetryable,
      )
      .recoverWith { case c: ConsensusNotReached =>
        LoggerUtil.logThrowableAtLevel(consensusFailureLogLevel, "Consensus not reached.", c)
        markBftCall("consensus_not_reached", connectionMetrics)
        Future.failed(
          HttpErrorWithHttpCode(
            StatusCodes.BadGateway,
            s"Failed to reach consensus from ${requestFrom.size} Scan nodes, " +
              s"requiring $nTargetSuccess matching responses.",
          )
        )
      }
      .andThen {
        case Failure(_: HttpErrorWithHttpCode) =>
        // Already marked by the recoverWith above ("consensus_not_reached")
        // or by the not_enough_scans branch — nothing more to do.
        case Failure(_) =>
          markBftCall("transport_error", connectionMetrics)
        case Success(_) =>
          markBftCall("ok", connectionMetrics)
      }
  }

  private def markBftCall(outcome: String, connectionMetrics: Option[ScanConnectionMetrics])(
      implicit mc: MetricsContext
  ): Unit =
    connectionMetrics.foreach { m =>
      MetricsContext.withExtraMetricLabels(("outcome", outcome)) { implicit mc =>
        m.bftCalls.mark()
      }
    }

  private def handleNotEnoughScans[T](
      connections: ScanConnections,
      callConfig: BftCallConfig,
      logLevel: Level,
      connectionMetrics: Option[ScanConnectionMetrics],
  )(implicit
      loggingContext: com.digitalasset.canton.logging.ErrorLoggingContext,
      mc: MetricsContext,
  ): Future[T] = {
    val totalNumber = connections.totalNumber
    val msg =
      s"Only ${callConfig.connections.size} scan instances can be used " +
        s"(out of $totalNumber configured ones), which are fewer than the necessary " +
        s"${callConfig.targetSuccess} to achieve BFT guarantees."
    val exception = HttpErrorWithHttpCode(StatusCodes.BadGateway, msg)
    LoggerUtil.logThrowableAtLevel(logLevel, msg, exception)
    markBftCall("not_enough_scans", connectionMetrics)
    Future.failed(exception)
  }

  private[client] def findScansWithAvailableData[C <: HasUrl](
      askFrom: Seq[C],
      logger: TracedLogger,
      hasData: C => Future[DataAvailabilityResponse],
      requiredNumber: Integer,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
      unreachable: Int = 0,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      mc: MetricsContext,
  ): Future[Seq[C]] = {

    val hasDataResponses =
      new ConcurrentHashMap[DataAvailabilityResponse, Seq[C]]()
    hasDataResponses.put(Available, Seq.empty)
    hasDataResponses.put(NotYet, Seq.empty)
    hasDataResponses.put(Never, Seq.empty)
    val finalResponse = Promise[Seq[C]]()
    val nResponsesDone = new AtomicInteger(0)

    /* For simplicity, we call hasData on all scans, not only `requiredNumber`.
       The assumption is that this call is cheap enough to afford calling all `n` scans.
       This does slightly reduce the randomness for which scan is eventually called for the actual data in the second phase,
       as we complete this phase as soon as `requiredNumber` scans report that they have the required data, and then use
       those (i.e. the firsts to respond) for the second phase, of fetching the actual data. */
    askFrom.foreach { scan =>
      hasData(scan)
        .transformWith {
          case Success(value) => Future.successful(value)
          // Failures are assumed to be temporary, hence translated into a "NotYet" response
          case Failure(f) =>
            logger.info(s"Failure from scan ${scan.url} treated as a data-not-yet-available: $f")
            Future.successful(NotYet)
        }
        .foreach(hasData => {
          logger.trace(s"Scan ${scan.url} data availability: $hasData.")
          val agreements = hasDataResponses.compute(
            hasData,
            (_, scans) => Option(scans).getOrElse(Seq.empty) :+ scan,
          )

          // to keep the logic simple, we complete the future early only on success. All failures/not-yet answers will be generated only once all scans respond.
          if (hasData == Available && agreements.size == requiredNumber) {
            logger.debug(
              s"Found enough scans with available data: ${agreements.map(_.url)}, completing the future with those"
            )
            finalResponse.tryComplete(Try(agreements)): Unit
          }

          if (nResponsesDone.incrementAndGet() == askFrom.size) { // all scans are done
            val notYet = hasDataResponses.get(NotYet).size + unreachable
            finalResponse.future.value match {
              case None if hasDataResponses.get(Available).size + notYet >= requiredNumber =>
                val msg =
                  s"Not enough scans have the data yet. ${hasDataResponses.get(Available).size} scans have data, ${hasDataResponses.get(NotYet).size} have responded with 'not yet', $unreachable are unreachable. Together that's at least the required $requiredNumber, so final result is 'not yet'"
                logger.debug(msg)
                val _ = finalResponse.tryFailure(
                  HttpErrorWithHttpCode(
                    StatusCodes.ServiceUnavailable,
                    msg,
                  )
                )
                markBftCall("not_yet", connectionMetrics)

              case None if notYet > 0 =>
                val msg =
                  s"Not enough scans will ever have the data, but some indicated that they will, just not yet, or are unreachable. Final result is therefore 'not yet' (if not enough will ever have data, we require all those that will to actually have it first). ${hasDataResponses
                      .get(Available)
                      .size} scans have data, ${hasDataResponses.get(NotYet).size} have responded with 'not yet', $unreachable are unreachable, ${hasDataResponses.get(Never).size} have responded with 'never'."
                logger.debug(msg)
                val _ = finalResponse.tryFailure(
                  HttpErrorWithHttpCode(
                    StatusCodes.ServiceUnavailable,
                    msg,
                  )
                )
                markBftCall("not_yet", connectionMetrics)

              case None if hasDataResponses.get(Available).isEmpty =>
                val msg = "All scans have responded with 'never'."
                logger.info(msg)
                val _ = finalResponse.tryFailure(new NoScanWillHaveData(msg))
                markBftCall("never", connectionMetrics)

              case None =>
                require(notYet == 0)
                val msg =
                  s"Not enough scans will ever have the data, but all those that will actually have it already. Returning those as the final response from phase 1. ${hasDataResponses
                      .get(Available)
                      .size} scans have data, ${hasDataResponses.get(Never).size} have responded with 'never'."
                logger.debug(msg)
                finalResponse.tryComplete(Try(hasDataResponses.get(Available))): Unit

              case Some(_) =>
              // Nothing to do. We completed the future already, and don't mark the bft call as complete yet, as we are moving to phase 2 where we fetch the actual data.
            }
          }
        })
    }
    finalResponse.future
  }

  private[client] def executeCall[T, C <: HasUrl](
      call: C => Future[T],
      requestFrom: Seq[C],
      nTargetSuccess: Int,
      logger: TracedLogger,
      shortenResponsesForLog: T => Any = identity[T],
      disagreementLogLevel: Level = Level.INFO,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
      mc: MetricsContext,
  ): Future[(T, List[Uri])] = {
    require(requestFrom.nonEmpty, "At least one request must be made.")

    val responses =
      new ConcurrentHashMap[ScanResponse[T], List[Uri]]()
    val nResponsesDone = new AtomicInteger(0)
    val finalResponse = Promise[(T, List[Uri])]()

    requestFrom.foreach { scan =>
      call(scan)
        .transformWith(response => keyToGroupResponses(response).map(_ -> response))
        .foreach { case (key, response) =>
          val agreements =
            responses.compute(
              key,
              (_, scans) => scan.url :: Option(scans).getOrElse(List.empty),
            )

          // In the special case of nTargetSuccess == 1, ignore error responses
          // Otherwise a single HTTP error or network failure would prevent reading the responses from others
          val considerResponseForQuorum = key match {
            case _: ExceptionFailureResponse[?] => !(nTargetSuccess == 1 && requestFrom.size != 1)
            case _ => true
          }
          if (considerResponseForQuorum && agreements.size == nTargetSuccess) { // consensus has been reached
            finalResponse.tryComplete(response.map(r => (r, agreements))): Unit
          }

          if (nResponsesDone.incrementAndGet() == requestFrom.size) { // all Scans are done
            finalResponse.future.value match {
              case None =>
                val exception = ConsensusNotReached(
                  requestFrom.size,
                  responses,
                  shortenResponsesForLog,
                )
                finalResponse.tryFailure(exception): Unit
              case Some(consensusResponse) =>
                logDisagreements(
                  logger,
                  consensusResponse.map(_._1),
                  responses,
                  disagreementLogLevel,
                  connectionMetrics,
                )
            }
          }
        }
    }
    finalResponse.future
  }

  /** Responses are stored in a ConcurrentHashMap. Equality is defined as:
    * - Simple Scala equality when the response is successful (typically, 200 OK).
    * - Status code + response body when the response is not successful (best effort).
    * - Never equal when there's other exceptions (unless those define equality, which they typically don't).
    */
  private def keyToGroupResponses[T](
      r1: Try[T]
  ): Future[ScanResponse[T]] = {
    r1 match {
      case Success(value) => Future.successful(SuccessfulResponse(value))
      case Failure(unexpected: BaseAppConnection.UnexpectedHttpNonJsonResponse) =>
        Future.successful(NonJsonHttpFailureResponse(unexpected.statusCode))
      case Failure(unexpected: BaseAppConnection.UnexpectedHttpTextResponse) =>
        Future.successful(
          TextFailureResponse(unexpected.statusCode, unexpected.content)
        )
      case Failure(unexpected: BaseAppConnection.UnexpectedHttpJsonResponse) =>
        Future.successful(
          HttpFailureResponse(unexpected.statusCode, unexpected.content)
        )
      case Failure(unexpected: HttpCommandException) =>
        Future.successful(
          HttpFailureResponse(
            unexpected.status,
            Json.obj("message" -> Json.fromString(unexpected.message)),
          )
        )
      case Failure(error) =>
        Future.successful(ExceptionFailureResponse(error))
    }
  }

  private def logDisagreements[T](
      logger: TracedLogger,
      consensusResponse: Try[T],
      responses: ConcurrentHashMap[ScanResponse[T], List[Uri]],
      disagreementLogLevel: Level,
      connectionMetrics: Option[ScanConnectionMetrics],
  )(implicit ec: ExecutionContext, tc: TraceContext, mc: MetricsContext): Unit = {
    implicit val elc: ErrorLoggingContext = ErrorLoggingContext.fromTracedLogger(logger)
    def recordConsensus(url: Uri, consensus: String, extraLabels: Map[String, String]): Unit =
      connectionMetrics.foreach { metrics =>
        val context = mc.merge(
          MetricsContext(
            Map(
              "scan_connection" -> url.authority.host.address(),
              "consensus" -> consensus,
            ) ++ extraLabels
          )
        )
        metrics.bftPerConnectionConsensus.mark()(context)
      }
    def disagreementLabels(response: ScanResponse[T]): Map[String, String] =
      response match {
        case _: SuccessfulResponse[?] => Map("success" -> "true")
        case HttpFailureResponse(status, _) =>
          Map("success" -> "false", "http_status" -> status.intValue.toString)
        case NonJsonHttpFailureResponse(status) =>
          Map("success" -> "false", "http_status" -> status.intValue.toString)
        case TextFailureResponse(status, _) =>
          Map("success" -> "false", "http_status" -> status.intValue.toString)
        case _: ExceptionFailureResponse[?] => Map("success" -> "false")
      }
    keyToGroupResponses(consensusResponse).foreach { consensusResponseKey =>
      val agreeingScanUrls = responses.remove(consensusResponseKey)
      agreeingScanUrls.foreach(recordConsensus(_, "agree", Map.empty))
      responses.forEach { (disagreeingResponse, scanUrls) =>
        val extraLabels = disagreementLabels(disagreeingResponse)
        scanUrls.foreach(recordConsensus(_, "disagree", extraLabels))
        LoggerUtil.logAtLevel(
          disagreementLogLevel,
          s"""The following Scan URLs disagreed with consensus:
             |${scanUrls.map(url => s"  $url").mkString("\n")}
             |consensus response: $consensusResponse
             |disagreeing response: $disagreeingResponse""".stripMargin,
        )
      }
    }
  }

  private sealed trait ScanResponse[+T]
  private case class SuccessfulResponse[+T](response: T) extends ScanResponse[T]
  private case class HttpFailureResponse[+T](status: StatusCode, body: Json) extends ScanResponse[T]
  private case class NonJsonHttpFailureResponse[+T](status: StatusCode) extends ScanResponse[T]
  private case class TextFailureResponse[+T](status: StatusCode, content: String)
      extends ScanResponse[T]
  private case class ExceptionFailureResponse[+T](error: Throwable) extends ScanResponse[T]

  private[client] class ConsensusNotReached(
      numRequests: Int,
      responses: Seq[(List[Uri], ScanResponse[?])],
  ) extends RuntimeException(
        s"Failed to reach consensus from $numRequests Scan nodes. Responses: $responses"
      )
  private[client] object ConsensusNotReached {
    def apply[T](
        numRequests: Int,
        responses: ConcurrentHashMap[ScanResponse[T], List[Uri]],
        shortenResponses: T => Any,
    ): ConsensusNotReached = {
      val shortResponses: Seq[(List[Uri], ScanResponse[?])] =
        responses.asScala.toSeq.map {
          case (SuccessfulResponse(response), uris) =>
            uris -> SuccessfulResponse(shortenResponses(response))
          case (HttpFailureResponse(status, body), uris) =>
            uris -> HttpFailureResponse(status, body)
          case (NonJsonHttpFailureResponse(status), uris) =>
            uris -> NonJsonHttpFailureResponse(status)
          case (TextFailureResponse(status, body), uris) =>
            uris -> TextFailureResponse(status, body)
          case (ExceptionFailureResponse(error), uris) => uris -> ExceptionFailureResponse(error)
        }

      new ConsensusNotReached(numRequests, shortResponses)
    }
  }

  private[client] object ConsensusNotReachedRetryable extends ExceptionRetryPolicy {
    override def determineExceptionErrorKind(exception: Throwable, logger: TracedLogger)(implicit
        tc: TraceContext
    ): ErrorKind = {
      exception match {
        case c: ConsensusNotReached =>
          logger.info("Consensus not reached. Will be retried.", c)
          ErrorKind.TransientErrorKind()
        case _ => ErrorKind.FatalErrorKind
      }
    }
  }

  case class MigrationInfoResponses(
      withData: Map[SingleScanConnection, SourceMigrationInfo],
      withoutData: Set[SingleScanConnection],
      unknownStatus: Set[SingleScanConnection],
  )

  /** getMigrationInfo is used for backfilling Scan data as part of SV onboarding.
    * It has its own unique BFT logic, to prevent multiple SVs onboarding in parallel
    * from live-locking each other by all not having the data thus breaking BFT
    */

  def getMigrationInfo(
      connections: ScanConnections,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
      retryProvider: RetryProvider,
      logger: TracedLogger,
      migrationId: Long,
  )(implicit
      loggingContext: com.digitalasset.canton.logging.ErrorLoggingContext,
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[SourceMigrationInfo]] = {
    for {
      // Ask ALL scans for the migration info
      responses <- getMigrationInfoResponses(connections, migrationId)
      result <-
        if (responses.withData.nonEmpty) {
          // At least one scan reported to have some data for the given migration id
          val completeResponses = responses.withData.filter { case (_, migrationInfo) =>
            migrationInfo.complete
          }
          val importUpdatesCompleteResponses = responses.withData.filter {
            case (_, migrationInfo) =>
              migrationInfo.importUpdatesComplete
          }
          for {
            // We already have the responses, use bftCall() to avoid re-implementing the consensus logic.
            // All non-malicious scans that have backfilled the input migrationId should return
            // the same value for previousMigrationId.
            previousMigrationId <- bftCallWithScanUris(
              connections,
              connectionMetrics,
              retryProvider,
              logger,
              connection => Future.successful(completeResponses(connection).previousMigrationId),
              "getMigrationInfo",
              BftCallConfig.forAvailableData(connections, completeResponses.contains),
              // This method is very sensitive to unavailable SVs.
              // Do not log warnings for failures to reach consensus, as this would be too noisy,
              // and instead rely on metrics to situations when backfilling is not progressing.
              consensusFailureLogLevel = Level.INFO,
              notEnoughScansLogLevel = Level.INFO,
            ).map(_._1)
            lastImportUpdateId <- bftCallWithScanUris(
              connections,
              connectionMetrics,
              retryProvider,
              logger,
              connection =>
                Future.successful(importUpdatesCompleteResponses(connection).lastImportUpdateId),
              "getMigrationInfo",
              BftCallConfig.forAvailableData(connections, importUpdatesCompleteResponses.contains),
              // This method is very sensitive to unavailable SVs.
              // Do not log warnings for failures to reach consensus, as this would be too noisy,
              // and instead rely on metrics to situations when backfilling is not progressing.
              consensusFailureLogLevel = Level.INFO,
              notEnoughScansLogLevel = Level.INFO,
            ).map(_._1)
          } yield {
            @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
            val unionOfRecordTimeRanges =
              responses.withData.values.map(_.recordTimeRange).reduce(_ |+| _)
            Some(
              SourceMigrationInfo(
                previousMigrationId = previousMigrationId,
                recordTimeRange = unionOfRecordTimeRanges,
                lastImportUpdateId = lastImportUpdateId,
                complete = completeResponses.nonEmpty,
                importUpdatesComplete = importUpdatesCompleteResponses.nonEmpty,
              )
            )
          }
        } else if (responses.withoutData.nonEmpty) {
          // All scans reported to have no data for the given migration id
          logger.info(
            s"All ${responses.withoutData.size} available scans reported to have no data for migration ${migrationId}"
          )
          Future.successful(None)
        } else {
          // No valid response from any scan
          val httpError =
            HttpErrorWithHttpCode(
              StatusCodes.BadGateway,
              s"No valid response from any scan.",
            )
          Future.failed(httpError)
        }
    } yield result
  }

  def getMigrationInfoResponses(connections: ScanConnections, migrationId: Long)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[MigrationInfoResponses] = for {
    results <- Future.traverse(connections.open)(connection =>
      connection
        .getMigrationInfo(migrationId)
        .transformWith(keyToGroupResponses)
        .map(result => connection -> result)
    )
  } yield {
    val (withData, other) =
      results.partitionMap { case (connection, response) =>
        response match {
          case SuccessfulResponse(Some(info)) =>
            Left(connection -> info)
          case SuccessfulResponse(None) =>
            Right(Left(connection))
          case _: HttpFailureResponse[?] | _: NonJsonHttpFailureResponse[?] |
              _: TextFailureResponse[?] | _: ExceptionFailureResponse[?] =>
            Right(Right(connection))
        }
      }
    val (withoutData, unknownStatus) = other partitionMap identity
    MigrationInfoResponses(
      withData.toMap,
      withoutData.toSet,
      unknownStatus.toSet,
    )
  }
}

trait HasUrl {
  def url: Uri
}
