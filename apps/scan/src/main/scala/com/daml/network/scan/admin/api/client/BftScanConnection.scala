package com.daml.network.scan.admin.api.client

import cats.data.{NonEmptyList, OptionT}
import cats.implicits.*
import com.daml.network.admin.http.HttpErrorWithHttpCode
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.coinrules.CoinRules
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cn.cns.{CnsEntry, CnsRules}
import com.daml.network.config.NetworkAppClientConfig
import com.daml.network.environment.PackageIdResolver.HasCoinRules
import com.daml.network.environment.{BaseAppConnection, CNLedgerClient, RetryFor, RetryProvider}
import com.daml.network.http.v0.definitions.MigrationSchedule
import com.daml.network.scan.admin.api.client.BftScanConnection.{ScanConnections, ScanList}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.SvcScan
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.scan.store.db.ScanAggregator
import com.daml.network.util.{Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.{Clock, PeriodicAction}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
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
    override protected val coinLedgerClient: CNLedgerClient,
    override protected val coinRulesCacheTimeToLive: NonNegativeFiniteDuration,
    val scanList: ScanList,
    protected val clock: Clock,
    val retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit protected val ec: ExecutionContextExecutor, protected val mat: Materializer)
    extends FlagCloseableAsync
    with NamedLogging
    with RetryProvider.Has
    with HasCoinRules
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

  override def getSvcPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] =
    bftCall(
      _.getSvcPartyId()
    )

  override protected def runGetCoinRulesWithState(
      cachedCoinRules: Option[ContractWithState[CoinRules.ContractId, CoinRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[CoinRules.ContractId, CoinRules]] =
    bftCall(
      _.getCoinRulesWithState(cachedCoinRules)
    )

  override protected def runGetCnsRules(
      cachedCnsRules: Option[ContractWithState[CnsRules.ContractId, CnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[CnsRules.ContractId, CnsRules]] = bftCall(
    _.getCnsRules(cachedCnsRules)
  )

  def lookupCnsEntryByParty(id: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[CnsEntry.ContractId, CnsEntry]]] =
    bftCall(_.lookupCnsEntryByParty(id))

  def lookupCnsEntryByName(name: String)(implicit
      tc: TraceContext
  ): Future[Option[Contract[CnsEntry.ContractId, CnsEntry]]] =
    bftCall(_.lookupCnsEntryByName(name))

  def listCnsEntries(namePrefix: Option[String], pageSize: Int)(implicit tc: TraceContext) =
    bftCall(_.listCnsEntries(namePrefix, pageSize))

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

  override def listSvcSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    bftCall(_.listSvcSequencers())
  }

  override def listSvcScans()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainScans]] = {
    bftCall(_.listSvcScans())
  }

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    bftCall(_.lookupFeaturedAppRight(providerPartyId))
  }

  override def listImportCrates(
      party: PartyId
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[ImportCrate.ContractId, ImportCrate]]] = {
    bftCall(_.listImportCrates(party))
  }

  // TODO(#9841) BFT
  def getRoundAggregate(round: Long)(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundAggregate]] = {
    logger.debug(s"Getting round aggregate for round $round")
    // gets aggregated rounds from all scans, ignoring calls that fail
    val scansWithRounds: Future[Map[SingleScanConnection, ScanAggregator.RoundRange]] = Future
      .sequence(scanList.scanConnections.open.map { scan =>
        scan
          .getAggregatedRounds()
          .map(_.map(scan -> _))
          .recover { case e: Throwable =>
            logger.info(s"Failed to get aggregated rounds from scan ${scan.config.adminApi.url}", e)
            None
          }
      })
      .map(_.flatten.toMap)
    // for calls that succeeded, get the round aggregates for the round from the scans that reported that they can serve it
    scansWithRounds.flatMap { scansWithRounds =>
      val roundsPerScan = scansWithRounds
        .map { case (s, r) => s.config.adminApi.url -> r }
      logger.debug(s"Aggregate rounds per scan: ${roundsPerScan}")
      if (scansWithRounds.isEmpty) {
        Future.failed(ScanAggregator.CannotAdvance("No scans have any aggregated rounds available"))
      } else {
        getRoundAggregatesWithinRound(scansWithRounds, round).flatMap { roundAggregates =>
          if (roundAggregates.isEmpty) {
            Future.failed(
              ScanAggregator.CannotAdvance(
                s"No RoundAggregates reported by ${scansWithRounds.size} scans"
              )
            )
          } else if (roundAggregates.toList.distinct.size == 1) {
            Future.successful(roundAggregates.headOption)
          } else {
            logger.warn(
              s"""The RoundAggregates reported by ${scansWithRounds.size} scans (${roundsPerScan.keys}) do not match:\n ${roundAggregates}"""
            )
            Future.successful(roundAggregates.headOption)
          }
        }
      }
    }
  }

  private def getRoundAggregatesWithinRound(
      scansWithRounds: Map[SingleScanConnection, ScanAggregator.RoundRange],
      round: Long,
  )(implicit
      tc: TraceContext
  ): Future[Iterable[ScanAggregator.RoundAggregate]] = {
    val scansHavingRound = scansWithRounds
      .filter(_._2.contains(round))
      .keys
      .toSeq
    def getRoundAggregateFromScan(scan: SingleScanConnection) = {
      logger
        .debug(
          s"Getting RoundAggregate for round $round from scan ${scan.config.adminApi.url}"
        )
      scan
        .getRoundAggregate(round)
        .flatMap {
          _.map(Future.successful).getOrElse(
            Future
              .failed(ScanAggregator.CannotAdvance(s"No RoundAggregate found for round $round"))
          )
        }
        .recoverWith { case e: Throwable =>
          logger.warn(
            s"Failed to get RoundAggregate for round $round from scan ${scan.config.adminApi.url}",
            e,
          )
          Future.failed(e)
        }
    }
    Future.traverse(scansHavingRound)(getRoundAggregateFromScan)
  }

  override def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule] = OptionT(bftCall(_.getMigrationSchedule().value))

  private def bftCall[T](
      call: SingleScanConnection => Future[T]
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[T] = {
    val connections @ ScanConnections(open, failed) = scanList.scanConnections
    val totalNumber = connections.totalNumber
    val f = connections.f
    if (failed > f) {
      val msg =
        s"Could not connect to $failed/$totalNumber Scans, which is above the threshold f=$f."
      val exception = HttpErrorWithHttpCode(
        StatusCodes.BadGateway,
        msg,
      )
      logger.warn(msg, exception)
      Future.failed(exception)
    } else {
      val nTargetSuccess = f + 1
      val nRequestsToDo = 2 * f + 1
      val requestFrom = Random.shuffle(open).take(nRequestsToDo)
      executeCall(call, requestFrom, nTargetSuccess)
    }
  }

  private def executeCall[T](
      call: SingleScanConnection => Future[T],
      requestFrom: Seq[SingleScanConnection],
      nTargetSuccess: Int,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[T] = {
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
              (_, scans) => scan.config.adminApi.url :: Option(scans).getOrElse(List.empty),
            )

          if (agreements.size == nTargetSuccess) { // consensus has been reached
            finalResponse.tryComplete(response): Unit
          }

          if (nResponsesDone.incrementAndGet() == requestFrom.size) { // all Scans are done
            finalResponse.future.value match {
              case None =>
                val exception = HttpErrorWithHttpCode(
                  StatusCodes.BadGateway,
                  s"Failed to reach consensus from ${requestFrom.size} Scan nodes.",
                )
                logger.warn(s"Consensus not reached. Responses: $responses", exception)
                finalResponse.tryFailure(exception): Unit
              case Some(consensusResponse) =>
                logDisagreements(consensusResponse, responses)
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
  private def keyToGroupResponses[T](r1: Try[T]): Future[BftScanConnection.ScanResponse] = {
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
      consensusResponse: Try[T],
      responses: ConcurrentHashMap[BftScanConnection.ScanResponse, List[Uri]],
  )(implicit tc: TraceContext): Unit = {
    keyToGroupResponses(consensusResponse).foreach { consensusResponseKey =>
      responses.remove(consensusResponseKey)
      responses.forEach { (disagreeingResponse, scanUrls) =>
        logger.info(
          s"Scans $scanUrls disagreed with the Consensus $consensusResponse and instead returned $disagreeingResponse"
        )
      }
    }
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    refreshAction.map(r => SyncCloseable("refresh_scan_list", r.close())).toList ++
      Seq[AsyncOrSyncCloseable](
        SyncCloseable("scan_list", scanList.close())
      )
  }

}

object BftScanConnection {

  case class ScanConnections(open: Seq[SingleScanConnection], failed: Int) {
    val totalNumber: Int = open.size + failed
    val f: Int = (totalNumber - 1) / 3
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

    /** Updates the scan list according to the scans present in the SvcRules.
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
      getScansInSvcRules(connection).flatMap { scansInSvcRules =>
        val newScans = scansInSvcRules.filter(scan => !currentScans.contains(scan.publicUrl))
        val removedScans = currentScans.filter(url => !scansInSvcRules.exists(_.publicUrl == url))
        if (scansInSvcRules.isEmpty) {
          // This is expected on app init, and is retried when building the BftScanConnection
          Future.failed(
            new IllegalStateException(
              s"Scan list in SvcRules is empty. Last known list: $currentState"
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
                toRetry.map { case (url, (_, svName)) => SvcScan(url, svName) }.toSeq
              )
          } yield {
            removedScans.foreach { url =>
              currentScanConnections.get(url).foreach { case (connection, svName) =>
                logger.info(
                  s"Closing connection to scan of $svName ($url) as it's been removed from the SvcRules scan list."
                )
                attemptToClose(connection)
              }
            }
            (newScansFailedConnections ++ retriedScansFailedConnections).foreach {
              case (url, (err, svName)) =>
                logger.warn(s"Failed to connect to scan of $svName ($url).", err)
            }

            val newState = BftState(
              currentScanConnections -- removedScans ++ newScansSuccessfulConnections ++ retriedScansSuccessfulConnections,
              (retriedScansFailedConnections ++ newScansFailedConnections).toMap,
            )
            currentScanConnectionsRef.set(newState)
            logger.info(s"Updated scan list to $newState")

            val connections = newState.scanConnections
            if (connections.failed > connections.f) {
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

    private def getScansInSvcRules(connection: BftScanConnection)(implicit tc: TraceContext) = {
      for {
        globalDomainId <- connection.getCoinRulesDomain()(tc)
        scans <- connection.listSvcScans()
        domainScans <- scans
          .find(_.domainId == globalDomainId)
          .map(e => Future.successful(e.scans))
          .getOrElse(
            Future.failed(
              new IllegalStateException(
                s"The global domain $globalDomainId is not present in the scans response: $scans"
              )
            )
          )
      } yield domainScans
    }

    /** Attempts to connect to all passed scans, returning two tuples containing the ones that failed to connect
      * and the ones that succeeded, respectively.
      */
    private def attemptConnections(
        scans: Seq[SvcScan]
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
      cnLedgerClient: CNLedgerClient,
      config: BftScanClientConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
  ): Future[BftScanConnection] = {
    val builder = buildScanConnection(clock, retryProvider, loggerFactory)
    config match {
      case BftScanClientConfig.TrustSingle(url, coinRulesCacheTimeToLive) =>
        // If this fails to connect, fail and let it retry
        val connectionF = builder(url, coinRulesCacheTimeToLive)
        connectionF
          .map(conn =>
            new BftScanConnection(
              cnLedgerClient,
              coinRulesCacheTimeToLive,
              new TrustSingle(conn, retryProvider, loggerFactory),
              clock,
              retryProvider,
              loggerFactory,
            )
          )
      case BftScanClientConfig.Bft(seedUrls, scansRefreshInterval, coinRulesCacheTimeToLive) =>
        for {
          bft <- seedUrls
            .traverse(uri =>
              builder(uri, coinRulesCacheTimeToLive).transformWith {
                case Success(conn) => Future.successful(Right(conn))
                case Failure(err) => Future.successful(Left(uri -> err))
              }
            )
            .map { cs =>
              val (failed, connections) = cs.toList.partitionEither(identity)
              new Bft(
                connections,
                failed.toMap,
                uri => builder(uri, coinRulesCacheTimeToLive),
                scansRefreshInterval,
                retryProvider,
                loggerFactory,
              )
            }
          bftConnection = new BftScanConnection(
            cnLedgerClient,
            coinRulesCacheTimeToLive,
            bft,
            clock,
            retryProvider,
            loggerFactory,
          )
          // start with the latest scan list
          _ <- retryProvider.waitUntil(
            RetryFor.WaitingOnInitDependency,
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
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
  ): (Uri, NonNegativeFiniteDuration) => Future[SingleScanConnection] =
    (uri: Uri, coinRulesCacheTimeToLive: NonNegativeFiniteDuration) => {
      ScanConnection.singleUncached( // BFTScanConnection caches itself so that caches don't desync
        ScanAppClientConfig(
          NetworkAppClientConfig(
            uri,
            failOnVersionMismatch = false,
          ),
          coinRulesCacheTimeToLive,
        ),
        clock,
        retryProvider,
        loggerFactory,
      )
    }

  sealed trait BftScanClientConfig
  object BftScanClientConfig {
    case class TrustSingle(
        url: Uri,
        coinRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultCoinRulesCacheTimeToLive,
    ) extends BftScanClientConfig
    case class Bft(
        seedUrls: NonEmptyList[Uri],
        scansRefreshInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
        coinRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultCoinRulesCacheTimeToLive,
    ) extends BftScanClientConfig

  }

  private sealed trait ScanResponse
  private case class SuccessfulResponse[T](response: T) extends ScanResponse
  private case class HttpFailureResponse(status: StatusCode, body: Json) extends ScanResponse
  private case class ExceptionFailureResponse(error: Throwable) extends ScanResponse
}
