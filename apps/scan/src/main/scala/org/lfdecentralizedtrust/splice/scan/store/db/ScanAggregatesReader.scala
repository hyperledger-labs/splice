// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import cats.data.NonEmptyList
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{
  BftScanConnection,
  ScanAggregatesConnection,
}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.lfdecentralizedtrust.splice.environment.{SpliceLedgerClient, RetryProvider}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.lifecycle.AsyncOrSyncCloseable
import com.digitalasset.canton.lifecycle.SyncCloseable

import java.util.concurrent.atomic.AtomicReference
import org.apache.pekko.stream.Materializer
import org.apache.pekko.http.scaladsl.model.Uri

import scala.concurrent.{Future, Promise}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.{Failure, Success}
import ScanAggregator.*
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.http.HttpClient

trait ScanAggregatesReader extends AutoCloseable {
  def readRoundAggregateFromDso(round: Long)(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Option[RoundAggregate]]
}

final case class ScanAggregatesReaderContext(
    clock: Clock,
    ledgerClient: SpliceLedgerClient,
    upgradesConfig: UpgradesConfig,
    loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    ec: ExecutionContextExecutor,
    mat: Materializer,
    httpClient: HttpClient,
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
    case class Initializing(promise: Promise[ScanAggregatesConnection]) extends ConState
    case class Initialized(connection: ScanAggregatesConnection) extends ConState
    case object Failed extends ConState
    private val conState = new AtomicReference[ConState](Uninitialized)

    import com.digitalasset.canton.discard.Implicits.DiscardOps
    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
      conState.get() match {
        case Uninitialized => Nil
        case Initializing(promise) =>
          Seq(
            SyncCloseable(
              "get_bft_scan_connection",
              promise
                .tryFailure(
                  new RuntimeException(
                    "Cancelled getScanAggregatesConnection attempt due to closeAsync"
                  )
                )
                .discard,
            )
          )
        case Initialized(connection) =>
          Seq(SyncCloseable("get_bft_scan_connection", connection.close()))
        case Failed => Nil
      }
    }

    def readRoundAggregateFromDso(round: Long)(implicit
        ec: ExecutionContext,
        traceContext: TraceContext,
    ): Future[Option[RoundAggregate]] = {
      for {
        con <- getScanAggregatesConnection()
        roundAggregate <- con.getRoundAggregate(round)
      } yield roundAggregate
    }

    def getScanAggregatesConnection()(implicit
        traceContext: TraceContext
    ): Future[ScanAggregatesConnection] = {
      conState.get() match {
        case Uninitialized =>
          val promise = Promise[ScanAggregatesConnection]()
          if (conState.compareAndSet(Uninitialized, Initializing(promise))) {
            createScanAggregatesConnection().onComplete {
              case Success(con) =>
                promise.success(con)
                conState.set(Initialized(con))
              case Failure(e) =>
                promise.failure(e)
                conState.set(Failed)
            }
          }
          getScanAggregatesConnection()
        case Initializing(promise) => promise.future
        case Initialized(connection) => Future.successful(connection)
        case Failed =>
          if (conState.compareAndSet(Failed, Uninitialized)) getScanAggregatesConnection()
          else Future.failed(new RuntimeException("Failed to get a BFT scan connection."))
      }
    }

    private def createScanAggregatesConnection()(implicit
        traceContext: TraceContext
    ): Future[ScanAggregatesConnection] = {
      for {
        decentralizedSynchronizerId <- store
          .getDecentralizedSynchronizerId()
          .map(_.unwrap.toProtoPrimitive)
        scans <- store.listDsoScans()
        scanUrls = scans
          .filter(_._1 == decentralizedSynchronizerId)
          .flatMap { case (_, scans) =>
            scans.map(si => Uri.parseAbsolute(si.publicUrl))
          }
          .toList
          .distinct
        nonEmptyScanUrls = NonEmptyList.fromList(scanUrls) match {
          case Some(urls) => Future.successful(urls)
          case None => Future.failed(CannotAdvance("No scan urls found in Dso Rules."))
        }
        config <- nonEmptyScanUrls.map(BftScanConnection.BftScanClientConfig.Bft(_))
        con <- ScanAggregatesConnection(
          context.ledgerClient,
          config,
          context.upgradesConfig,
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
