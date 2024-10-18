// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.data.{NonEmptyList, OptionT}
import cats.implicits.*
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.ExternalPartyAmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.environment.{
  BaseAppConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{AnsEntry, MigrationSchedule}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.{
  ConsensusNotReached,
  ConsensusNotReachedRetryable,
  ScanConnections,
  ScanList,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DsoScan
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.util.{Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.time.{Clock, PeriodicAction}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry.{ExceptionRetryPolicy, ErrorKind}
import io.circe.Json
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Random, Success, Try}

class BftScanConnection(
    override protected val amuletLedgerClient: SpliceLedgerClient,
    override protected val amuletRulesCacheTimeToLive: NonNegativeFiniteDuration,
    val scanList: ScanList,
    protected val clock: Clock,
    val retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit protected val ec: ExecutionContextExecutor, protected val mat: Materializer)
    extends FlagCloseableAsync
    with NamedLogging
    with RetryProvider.Has
    with HasAmuletRules
    with CachingScanConnection {

  private val refreshAction: Option[PeriodicAction] = scanList match {
    case _: BftScanConnection.TrustSingle =>
      None
    case bft: BftScanConnection.Bft =>
      Some(
        new PeriodicAction(
          clock,
          com.digitalasset.canton.time.NonNegativeFiniteDuration
            .fromConfig(bft.scansRefreshInterval),
          loggerFactory,
          retryProvider.timeouts,
          "refresh_scan_list",
        )({ tc =>
          // This retry makes sure any partial or complete failures are immediately retried with a backoff.
          retryProvider.retry(
            RetryFor.LongRunningAutomation,
            "refresh_scan_list",
            "refresh_scan_list",
            bft.refresh(this)(tc).flatMap { connections =>
              if (connections.failed > 0)
                Future.failed(
                  io.grpc.Status.UNAVAILABLE
                    .withDescription("Deliberately enforcing a retry on failed scans.")
                    .asRuntimeException()
                )
              else Future.unit
            },
            logger,
          )(implicitly, TraceContext.empty, implicitly)
        })
      )
  }

  override def getDsoPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] =
    bftCall(
      _.getDsoPartyId()
    )

  override protected def runGetAmuletRulesWithState(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] =
    bftCall(
      _.getAmuletRulesWithState(cachedAmuletRules)
    )

  override protected def runGetExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  )(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] =
    bftCall(
      _.getExternalPartyAmuletRules(cachedExternalPartyAmuletRules)
    )

  override protected def runGetAnsRules(
      cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AnsRules.ContractId, AnsRules]] = bftCall(
    _.getAnsRules(cachedAnsRules)
  )

  def lookupAnsEntryByParty(id: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[AnsEntry]] =
    bftCall(_.lookupAnsEntryByParty(id))

  def lookupAnsEntryByName(name: String)(implicit
      tc: TraceContext
  ): Future[Option[AnsEntry]] =
    bftCall(_.lookupAnsEntryByName(name))

  def listAnsEntries(namePrefix: Option[String], pageSize: Int)(implicit
      tc: TraceContext
  ): Future[Seq[AnsEntry]] =
    bftCall(_.listAnsEntries(namePrefix, pageSize))

  override protected def runGetOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ] = bftCall(_.getOpenAndIssuingMiningRounds(cachedOpenRounds, cachedIssuingRounds))

  override def listDsoSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    bftCall(_.listDsoSequencers())
  }

  override def listDsoScans()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainScans]] = {
    bftCall(_.listDsoScans())
  }

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    bftCall(_.lookupFeaturedAppRight(providerPartyId))
  }

  override def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule] = OptionT(bftCall(_.getMigrationSchedule().value))

  override def lookupTransferPreapprovalByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]] =
    bftCall(_.lookupTransferPreapprovalByParty(receiver))

  private def bftCall[T](
      call: SingleScanConnection => Future[T]
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[T] = {
    val connections @ ScanConnections(open, _) = scanList.scanConnections
    val f = connections.f
    if (!connections.enoughAvailableScans) {
      val totalNumber = connections.totalNumber
      val msg =
        s"Only ${open.size} scan instances are reachable (out of $totalNumber configured ones), which are fewer than the necessary ${f + 1} to achieve BFT guarantees."
      val exception = HttpErrorWithHttpCode(
        StatusCodes.BadGateway,
        msg,
      )
      logger.warn(msg, exception)
      Future.failed(exception)
    } else {
      val nRequestsToDo = 2 * f + 1
      retryProvider
        .retryForClientCalls(
          "bft_call",
          s"Bft call with f $f",
          BftScanConnection.executeCall(
            call,
            Random.shuffle(open).take(nRequestsToDo),
            nTargetSuccess = f + 1,
            logger,
          ),
          logger,
          (_: String) => ConsensusNotReachedRetryable,
        )
        .recoverWith { case c: ConsensusNotReached =>
          val httpError = HttpErrorWithHttpCode(
            StatusCodes.BadGateway,
            s"Failed to reach consensus from $nRequestsToDo Scan nodes.",
          )
          logger.warn(s"Consensus not reached.", c)
          Future.failed(httpError)
        }
    }
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    refreshAction.map(r => SyncCloseable("refresh_scan_list", r.close())).toList ++
      Seq[AsyncOrSyncCloseable](
        SyncCloseable("scan_list", scanList.close())
      )
  }
}
trait HasUrl {
  def url: Uri
}

object BftScanConnection {
  def executeCall[T, C <: HasUrl](
      call: C => Future[T],
      requestFrom: Seq[C],
      nTargetSuccess: Int,
      logger: TracedLogger,
  )(implicit ec: ExecutionContext, tc: TraceContext, mat: Materializer): Future[T] = {
    require(requestFrom.nonEmpty, "At least one request must be made.")

    val responses =
      new ConcurrentHashMap[BftScanConnection.ScanResponse, List[Uri]]()
    val nResponsesDone = new AtomicInteger(0)
    val finalResponse = Promise[T]()

    requestFrom.foreach { scan =>
      call(scan)
        .transformWith(response => keyToGroupResponses(response).map(_ -> response))
        .foreach { case (key, response) =>
          val agreements =
            responses.compute(
              key,
              (_, scans) => scan.url :: Option(scans).getOrElse(List.empty),
            )

          if (agreements.size == nTargetSuccess) { // consensus has been reached
            finalResponse.tryComplete(response): Unit
          }

          if (nResponsesDone.incrementAndGet() == requestFrom.size) { // all Scans are done
            finalResponse.future.value match {
              case None =>
                val exception = new ConsensusNotReached(requestFrom.size, responses)
                finalResponse.tryFailure(exception): Unit
              case Some(consensusResponse) =>
                logDisagreements(logger, consensusResponse, responses)
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
  )(implicit ec: ExecutionContext, mat: Materializer): Future[BftScanConnection.ScanResponse] = {
    r1 match {
      case Success(value) => Future.successful(BftScanConnection.SuccessfulResponse(value))
      case Failure(unexpected: BaseAppConnection.UnexpectedHttpResponse)
          if unexpected.response.entity.contentType.mediaType == MediaTypes.`application/json` =>
        Unmarshal(unexpected.response.entity)
          .to[ByteString]
          .flatMap(s =>
            io.circe.jawn.parseByteBuffer(s.asByteBuffer) match {
              case Right(value) =>
                Future.successful(
                  BftScanConnection.HttpFailureResponse(unexpected.response.status, value)
                )
              case Left(failure) =>
                Future.successful(BftScanConnection.ExceptionFailureResponse(failure))
            }
          )
      case Failure(error) =>
        Future.successful(BftScanConnection.ExceptionFailureResponse(error))
    }
  }

  private def logDisagreements[T](
      logger: TracedLogger,
      consensusResponse: Try[T],
      responses: ConcurrentHashMap[BftScanConnection.ScanResponse, List[Uri]],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Unit = {
    keyToGroupResponses(consensusResponse).foreach { consensusResponseKey =>
      responses.remove(consensusResponseKey)
      responses.forEach { (disagreeingResponse, scanUrls) =>
        logger.info(
          s"Scans $scanUrls disagreed with the Consensus $consensusResponse and instead returned $disagreeingResponse"
        )
      }
    }
  }

  case class ScanConnections(open: Seq[SingleScanConnection], failed: Int) {
    val totalNumber: Int = open.size + failed
    val f: Int = (totalNumber - 1) / 3
    val enoughAvailableScans: Boolean = open.size > f
  }

  private[BftScanConnection] sealed trait ScanList
      extends FlagCloseableAsync
      with NamedLogging
      with RetryProvider.Has {
    def scanConnections: ScanConnections
  }
  class TrustSingle(
      scanConnection: SingleScanConnection,
      val retryProvider: RetryProvider,
      val loggerFactory: NamedLoggerFactory,
  ) extends ScanList {
    override def scanConnections: ScanConnections = ScanConnections(Seq(scanConnection), 0)

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
      SyncCloseable("scan_connection", scanConnection.close())
    )
  }

  type SvName = String
  case class BftState(
      openConnections: Map[Uri, (SingleScanConnection, SvName)],
      failedConnections: Map[Uri, (Throwable, SvName)],
  ) {
    def scanConnections: ScanConnections =
      ScanConnections(openConnections.values.map(_._1).toSeq, failedConnections.size)
  }

  class Bft(
      initialScanConnections: Seq[SingleScanConnection],
      initialFailedConnections: Map[Uri, Throwable],
      connectionBuilder: Uri => Future[SingleScanConnection],
      val scansRefreshInterval: NonNegativeFiniteDuration,
      val retryProvider: RetryProvider,
      val loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext)
      extends ScanList {

    private val currentScanConnectionsRef: AtomicReference[BftState] =
      new AtomicReference(
        BftState(
          initialScanConnections.zipWithIndex.map { case (conn, n) =>
            (conn.config.adminApi.url, (conn, s"Seed URL #$n"))
          }.toMap,
          initialFailedConnections.zipWithIndex.map { case ((uri, err), n) =>
            uri -> (err, s"FAILED Seed URL #$n")
          }.toMap,
        )
      )

    /** Updates the scan list according to the scans present in the DsoRules.
      * Additionally, if any previous connections to a Scan failed, they're retried.
      * This method should only be called once when creating a BftScanConnection and periodically by PeriodicAction,
      *  which ensures that there's never two concurrent calls to it.
      */
    def refresh(
        connection: BftScanConnection
    )(implicit tc: TraceContext): Future[ScanConnections] = {
      val currentState @ BftState(currentScanConnections, currentFailed) =
        currentScanConnectionsRef.get()
      val currentScans = (currentScanConnections.keys ++ currentFailed.keys).toSet
      logger.info(s"Started refreshing scan list from $currentState")
      getScansInDsoRules(connection).flatMap { scansInDsoRules =>
        val newScans = scansInDsoRules.filter(scan => !currentScans.contains(scan.publicUrl))
        val removedScans = currentScans.filter(url => !scansInDsoRules.exists(_.publicUrl == url))
        if (scansInDsoRules.isEmpty) {
          // This is expected on app init, and is retried when building the BftScanConnection
          Future.failed(
            new IllegalStateException(
              s"Scan list in DsoRules is empty. Last known list: $currentState"
            )
          )
        } else if (newScans.isEmpty && removedScans.isEmpty && currentFailed.isEmpty) {
          logger.debug("Not updating scan list as there are no changes.")
          Future.successful(currentState.scanConnections)
        } else {
          for {
            (newScansFailedConnections, newScansSuccessfulConnections) <- attemptConnections(
              newScans
            )
            toRetry = currentFailed -- removedScans
            (retriedScansFailedConnections, retriedScansSuccessfulConnections) <-
              attemptConnections(
                toRetry.map { case (url, (_, svName)) => DsoScan(url, svName) }.toSeq
              )
          } yield {
            removedScans.foreach { url =>
              currentScanConnections.get(url).foreach { case (connection, svName) =>
                logger.info(
                  s"Closing connection to scan of $svName ($url) as it's been removed from the DsoRules scan list."
                )
                attemptToClose(connection)
              }
            }
            (newScansFailedConnections ++ retriedScansFailedConnections).foreach {
              case (url, (err, svName)) =>
                // TODO(#10660): abstract this pattern into the RetryProvider
                if (retryProvider.isClosing)
                  logger.info(
                    s"Suppressed warning, as we're shutting down: Failed to connect to scan of $svName ($url).",
                    err,
                  )
                else
                  logger.warn(s"Failed to connect to scan of $svName ($url).", err)
            }

            val newState = BftState(
              currentScanConnections -- removedScans ++ newScansSuccessfulConnections ++ retriedScansSuccessfulConnections,
              (retriedScansFailedConnections ++ newScansFailedConnections).toMap,
            )
            currentScanConnectionsRef.set(newState)
            logger.info(s"Updated scan list to $newState")

            val connections = newState.scanConnections
            if (!connections.enoughAvailableScans) {
              throw io.grpc.Status.FAILED_PRECONDITION
                .withDescription(
                  s"There are not enough Scans to satisfy f=${connections.f}. Will be retried. State: $newState"
                )
                .asRuntimeException()
            } else {
              connections
            }
          }
        }
      }
    }

    private def getScansInDsoRules(connection: BftScanConnection)(implicit tc: TraceContext) = {
      for {
        decentralizedSynchronizerId <- connection.getAmuletRulesDomain()(tc)
        scans <- connection.listDsoScans()
        domainScans <- scans
          .find(_.domainId == decentralizedSynchronizerId)
          .map(e => Future.successful(e.scans))
          .getOrElse(
            Future.failed(
              new IllegalStateException(
                s"The global domain $decentralizedSynchronizerId is not present in the scans response: $scans"
              )
            )
          )
      } yield domainScans
    }

    /** Attempts to connect to all passed scans, returning two tuples containing the ones that failed to connect
      * and the ones that succeeded, respectively.
      */
    private def attemptConnections(
        scans: Seq[DsoScan]
    )(implicit
        tc: TraceContext
    ): Future[(Seq[(Uri, (Throwable, SvName))], Seq[(Uri, (SingleScanConnection, SvName))])] = {
      scans
        .traverse { scan =>
          logger.info(s"Attempting to connect to Scan: $scan.")
          connectionBuilder(scan.publicUrl)
            .transformWith(result =>
              Future.successful(
                result.toEither
                  .bimap(scan.publicUrl -> (_, scan.svName), scan.publicUrl -> (_, scan.svName))
              )
            )
        }
        .map(_.partitionEither(identity))
    }

    private def attemptToClose(
        connection: SingleScanConnection
    )(implicit tc: TraceContext): Unit = {
      try {
        connection.close()
      } catch {
        case NonFatal(ex) =>
          logger.warn(s"Failed to close connection to scan ${connection.config.adminApi.url}", ex)
      }
    }

    override def scanConnections: ScanConnections = {
      currentScanConnectionsRef.get().scanConnections
    }

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
      initialScanConnections.zipWithIndex.map { case (connection, i) =>
        SyncCloseable(s"scan_connection_$i", connection.close())
      }
  }

  def apply(
      spliceLedgerClient: SpliceLedgerClient,
      config: BftScanClientConfig,
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[BftScanConnection] = {
    val builder = buildScanConnection(upgradesConfig, clock, retryProvider, loggerFactory)
    config match {
      case BftScanClientConfig.TrustSingle(url, amuletRulesCacheTimeToLive) =>
        // If this fails to connect, fail and let it retry
        val connectionF = builder(url, amuletRulesCacheTimeToLive)
        connectionF
          .map(conn =>
            new BftScanConnection(
              spliceLedgerClient,
              amuletRulesCacheTimeToLive,
              new TrustSingle(conn, retryProvider, loggerFactory),
              clock,
              retryProvider,
              loggerFactory,
            )
          )
      case BftScanClientConfig.Bft(seedUrls, scansRefreshInterval, amuletRulesCacheTimeToLive) =>
        for {
          bft <- seedUrls
            .traverse(uri =>
              builder(uri, amuletRulesCacheTimeToLive).transformWith {
                case Success(conn) => Future.successful(Right(conn))
                case Failure(err) => Future.successful(Left(uri -> err))
              }
            )
            .map { cs =>
              val (failed, connections) = cs.toList.partitionEither(identity)
              new Bft(
                connections,
                failed.toMap,
                uri => builder(uri, amuletRulesCacheTimeToLive),
                scansRefreshInterval,
                retryProvider,
                loggerFactory,
              )
            }
          bftConnection = new BftScanConnection(
            spliceLedgerClient,
            amuletRulesCacheTimeToLive,
            bft,
            clock,
            retryProvider,
            loggerFactory,
          )
          // start with the latest scan list
          _ <- retryProvider.waitUntil(
            RetryFor.WaitingOnInitDependency,
            "refresh_scan_list",
            "Scan list is refreshed.",
            bft
              .refresh(bftConnection)
              .recoverWith { case NonFatal(ex) =>
                Future.failed(
                  Status.UNAVAILABLE
                    .withDescription("Failed to refresh scan list on init")
                    .withCause(ex)
                    .asException()
                )
              }
              .map(_ => ()),
            loggerFactory.getTracedLogger(classOf[BftScanConnection]),
          )
        } yield bftConnection
    }
  }

  private def buildScanConnection(
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): (Uri, NonNegativeFiniteDuration) => Future[SingleScanConnection] =
    (uri: Uri, amuletRulesCacheTimeToLive: NonNegativeFiniteDuration) =>
      ScanConnection
        .singleUncached( // BFTScanConnection caches itself so that caches don't desync
          ScanAppClientConfig(
            NetworkAppClientConfig(
              uri
            ),
            amuletRulesCacheTimeToLive,
          ),
          upgradesConfig,
          clock,
          retryProvider,
          loggerFactory,
          // We only need f+1 Scans to be available, so so as long as those are connected we don't need to slow init down.
          // Furthermore, the refresh (either on init, or periodically) will retry anyway.
          retryConnectionOnInitialFailure = false,
        )

  sealed trait BftScanClientConfig
  object BftScanClientConfig {
    case class TrustSingle(
        url: Uri,
        amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
    ) extends BftScanClientConfig
    case class Bft(
        seedUrls: NonEmptyList[Uri],
        scansRefreshInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
        amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
    ) extends BftScanClientConfig

  }

  private sealed trait ScanResponse
  private case class SuccessfulResponse[T](response: T) extends ScanResponse
  private case class HttpFailureResponse(status: StatusCode, body: Json) extends ScanResponse
  private case class ExceptionFailureResponse(error: Throwable) extends ScanResponse

  class ConsensusNotReached(
      numRequests: Int,
      responses: ConcurrentHashMap[BftScanConnection.ScanResponse, List[Uri]],
  ) extends RuntimeException(
        s"Failed to reach consensus from $numRequests Scan nodes. Responses: $responses"
      )

  object ConsensusNotReachedRetryable extends ExceptionRetryPolicy {
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
}
