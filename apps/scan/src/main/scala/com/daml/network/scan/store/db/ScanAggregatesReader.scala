package com.daml.network.scan.store.db

import cats.data.NonEmptyList
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.util.TemplateJsonDecoder
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.lifecycle.AsyncOrSyncCloseable
import com.digitalasset.canton.lifecycle.SyncCloseable
import org.apache.pekko.stream.Materializer
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.Uri
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.Success
import scala.util.Failure
import ScanAggregator.*

// TODO(#9842) Add a trigger that backfills aggregates from svc (add methods to ScanAggregatesReader to support this)
trait ScanAggregatesReader {
  def readRoundAggregateFromSvc(round: Long)(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Option[RoundAggregate]]
}

final case class ScanAggregatesReaderContext(
    clock: Clock,
    ledgerClient: CNLedgerClient,
    loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    ec: ExecutionContextExecutor,
    mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateJsonDecoder: TemplateJsonDecoder,
)
object ScanAggregatesReader {
  def apply(store: DbScanStore, context: ScanAggregatesReaderContext): ScanAggregatesReader =
    new DbScanAggregatesReader(store, context)

  final class DbScanAggregatesReader(store: DbScanStore, context: ScanAggregatesReaderContext)
      extends ScanAggregatesReader
      with FlagCloseableAsync {
    implicit val ec: ExecutionContextExecutor = context.ec
    override protected def timeouts: ProcessingTimeout = context.retryProvider.timeouts
    override protected def logger: TracedLogger = context.loggerFactory.getTracedLogger(getClass)

    sealed trait ConState
    case object Uninitialized extends ConState
    case class Initializing(promise: Promise[BftScanConnection]) extends ConState
    case class Initialized(connection: BftScanConnection) extends ConState
    case object Failed extends ConState
    private val conState = new AtomicReference[ConState](Uninitialized)

    import com.digitalasset.canton.DiscardOps
    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
      conState.get() match {
        case Uninitialized => Nil
        case Initializing(promise) =>
          Seq(
            SyncCloseable(
              "get_bft_scan_connection",
              promise
                .tryFailure(
                  new RuntimeException("Cancelled getBftScanConnection attempt due to closeAsync")
                )
                .discard,
            )
          )
        case Initialized(connection) =>
          Seq(SyncCloseable("get_bft_scan_connection", connection.close()))
        case Failed => Nil
      }
    }

    def readRoundAggregateFromSvc(round: Long)(implicit
        ec: ExecutionContext,
        traceContext: TraceContext,
    ): Future[Option[RoundAggregate]] = {
      for {
        con <- getBftScanConnection()
        roundAggregate <- con.getRoundAggregate(round)
      } yield roundAggregate
    }

    def getBftScanConnection()(implicit traceContext: TraceContext): Future[BftScanConnection] = {
      conState.get() match {
        case Uninitialized =>
          val promise = Promise[BftScanConnection]()
          if (conState.compareAndSet(Uninitialized, Initializing(promise))) {
            createBftScanConnection().onComplete {
              case Success(con) =>
                promise.success(con)
                conState.set(Initialized(con))
              case Failure(e) =>
                promise.failure(e)
                conState.set(Failed)
            }
          }
          getBftScanConnection()
        case Initializing(promise) => promise.future
        case Initialized(connection) => Future.successful(connection)
        case Failed =>
          if (conState.compareAndSet(Failed, Uninitialized)) getBftScanConnection()
          else Future.failed(new RuntimeException("Failed to get a BFT scan connection."))
      }
    }

    private def createBftScanConnection()(implicit
        traceContext: TraceContext
    ): Future[BftScanConnection] = {
      for {
        svcScans <- store
          .listSvcScans()
        scanUrls = svcScans.flatMap { case (_, scans) =>
          scans.map(si => Uri.parseAbsolute(si.publicUrl))
        }.toList
        nonEmptyScanUrls = NonEmptyList.fromList(scanUrls) match {
          case Some(urls) => Future.successful(urls)
          case None => Future.failed(CannotAdvance("No scan urls found in Svc Rules."))
        }
        config <- nonEmptyScanUrls.map(BftScanConnection.BftScanClientConfig.Bft(_))
        con <- BftScanConnection(
          context.ledgerClient,
          config,
          context.clock,
          context.retryProvider,
          context.loggerFactory,
        )(
          context.ec,
          traceContext,
          context.mat,
          context.httpClient,
          context.templateJsonDecoder,
        )
      } yield con
    }
  }
}
