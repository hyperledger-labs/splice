package com.daml.network.scan.admin.api.client

import cats.data.NonEmptyList
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
import com.daml.network.scan.admin.api.client.BftScanConnection.ScanList
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.SvcScan
import com.daml.network.scan.config.ScanAppClientConfig
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
    scanList: ScanList,
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
          bft.refresh(this)(tc).andThen { case Failure(ex) =>
            logger.warn("Failed to refresh scan list", ex)(tc)
          }
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

  private def bftCall[T](
      call: SingleScanConnection => Future[T]
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[T] = {
    val connections = scanList.scanConnections()
    val f = (connections.size - 1) / 3
    val nTargetSuccess = f + 1
    val nRequestsToDo = 2 * f + 1
    val requestFrom = Random.shuffle(connections).take(nRequestsToDo)

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

          if (nResponsesDone.incrementAndGet() == nRequestsToDo) { // all Scans are done
            finalResponse.future.value match {
              case None =>
                val exception = HttpErrorWithHttpCode(
                  StatusCodes.BadGateway,
                  s"Failed to reach consensus from $nRequestsToDo Scan nodes.",
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

  private[BftScanConnection] sealed trait ScanList
      extends FlagCloseableAsync
      with NamedLogging
      with RetryProvider.Has {
    def scanConnections(): Seq[SingleScanConnection]
  }
  class TrustSingle(
      scanConnection: SingleScanConnection,
      val retryProvider: RetryProvider,
      val loggerFactory: NamedLoggerFactory,
  ) extends ScanList {
    override def scanConnections(): Seq[SingleScanConnection] = Seq(scanConnection)
    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
      SyncCloseable("scan_connection", scanConnection.close())
    )
  }
  class Bft(
      initialScanConnections: Seq[SingleScanConnection],
      connectionBuilder: Uri => Future[SingleScanConnection],
      val scansRefreshInterval: NonNegativeFiniteDuration,
      val retryProvider: RetryProvider,
      val loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext)
      extends ScanList {
    type SvName = String
    private val currentScanConnectionsRef
        : AtomicReference[Map[Uri, (SingleScanConnection, SvName)]] =
      new AtomicReference(
        initialScanConnections.zipWithIndex.map { case (conn, n) =>
          (conn.config.adminApi.url, (conn, s"Seed URL #$n"))
        }.toMap
      )

    /** This method should only be called once when creating a BftScanConnection and periodically by PeriodicAction,
      *  which ensures that there's never two concurrent calls to it.
      */
    def refresh(connection: BftScanConnection)(implicit tc: TraceContext): Future[Unit] = {
      val currentScanConnections = currentScanConnectionsRef.get()
      logger.info(s"Started refreshing scan list from ${currentScanConnections.keys}")
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
        addedScans <- connectToAllScansOrFail(
          domainScans.filter(scan => !currentScanConnections.contains(scan.publicUrl))
        )
        (alreadyPresentScans, removedScans) = currentScanConnections.partition { case (url, _) =>
          domainScans.exists(_.publicUrl == url)
        }
        finalScans = alreadyPresentScans ++ addedScans
        _ <-
          if (finalScans.isEmpty) {
            // This is expected on app init, and is retried when building the BftScanConnection
            val lastKnown = currentScanConnections.map { case (url, (_, svName)) =>
              SvcScan(url, svName)
            }
            Future.failed(
              new IllegalStateException(
                s"Scan list in SvcRules is empty. Last known list: $lastKnown"
              )
            )
          } else Future.unit
      } yield {
        removedScans.foreach { case (url, (connection, svName)) =>
          logger.info(
            s"Closing connection to scan of $svName ($url) as it's been removed from the SvcRules scan list."
          )
          attemptToClose(connection)
        }
        if (currentScanConnections.keySet != finalScans.keySet) {
          currentScanConnectionsRef.set(finalScans)
          logger.info(s"Updated scan list to $finalScans")
        }
      }
    }

    private def connectToAllScansOrFail(
        scans: Seq[SvcScan]
    )(implicit tc: TraceContext): Future[Seq[(Uri, (SingleScanConnection, SvName))]] = {
      for {
        addedScans <- scans
          .traverse { scan =>
            logger.info(s"New Scan in SvcRules scan list: $scan, creating connection.")
            connectionBuilder(scan.publicUrl)
              .map { connection =>
                (connection, scan.svName)
              }
              .transformWith(result =>
                Future.successful(result.toEither.bimap(scan.publicUrl -> _, scan.publicUrl -> _))
              )
          }
        (failedToConnect, successfullyConnected) = addedScans.partitionMap(identity)
        _ <-
          if (failedToConnect.nonEmpty) {
            failedToConnect.foreach { case (uri, failure) =>
              logger.warn(s"Failed to create connection to scan $uri", failure)
            }
            successfullyConnected.foreach { case (url, (connection, svName)) =>
              logger.info(
                s"Closing connection to scan of $svName ($url), to avoid resource leakage."
              )
              attemptToClose(connection)
            }
            Future.failed(
              new RuntimeException(
                s"Failed to connect to all scans from scan list: $failedToConnect."
              )
            )
          } else Future.unit
      } yield successfullyConnected
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

    override def scanConnections(): Seq[SingleScanConnection] =
      currentScanConnectionsRef.get().values.map(_._1).toSeq

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
            .traverse(builder(_, coinRulesCacheTimeToLive))
            .map(cs =>
              new Bft(
                cs.toList,
                uri => builder(uri, coinRulesCacheTimeToLive),
                scansRefreshInterval,
                retryProvider,
                loggerFactory,
              )
            )
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
            bft.refresh(bftConnection).recoverWith { case NonFatal(ex) =>
              Future.failed(
                Status.UNAVAILABLE
                  .withDescription("Failed to refresh scan list on init")
                  .withCause(ex)
                  .asException()
              )
            },
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
