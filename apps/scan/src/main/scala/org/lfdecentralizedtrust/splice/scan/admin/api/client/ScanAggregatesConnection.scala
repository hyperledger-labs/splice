// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{
  BftScanConnection,
  SingleScanConnection,
}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.lfdecentralizedtrust.splice.environment.{SpliceLedgerClient, RetryProvider}
import org.lfdecentralizedtrust.splice.http.HttpClient
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.lifecycle.AsyncOrSyncCloseable
import org.apache.pekko.stream.Materializer

import scala.concurrent.Future
import scala.concurrent.ExecutionContextExecutor
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator.*

object ScanAggregatesConnection {
  def apply(
      spliceLedgerClient: SpliceLedgerClient,
      config: BftScanConnection.BftScanClientConfig,
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
  ): Future[ScanAggregatesConnection] = {
    BftScanConnection(
      spliceLedgerClient,
      config,
      upgradesConfig,
      clock,
      retryProvider,
      loggerFactory,
    )
      .map(bft => new ScanAggregatesConnection(bft, retryProvider, loggerFactory))
  }
}

class ScanAggregatesConnection(
    bftScanConnection: BftScanConnection,
    val retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit val ec: ExecutionContextExecutor, val mat: Materializer)
    extends NamedLogging
    with RetryProvider.Has
    with FlagCloseableAsync {
  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = bftScanConnection.closeAsync()
  private val scanList = bftScanConnection.scanList

  def getRoundAggregate(round: Long)(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundAggregate]] = {

    logger.debug(s"Getting round aggregate for round $round")

    // gets aggregated rounds from all scans, ignoring scans that are not reachable and calls that fail
    val scansWithRounds: Future[Map[SingleScanConnection, ScanAggregator.RoundRange]] = Future
      .sequence(scanList.scanConnections.open.map { scan =>
        scan
          .getAggregatedRounds()
          .map(_.map(scan -> _))
          .recover { case e: Throwable =>
            logger.info(
              s"Failed to get aggregated rounds from scan ${scan.url}, while getting round aggregate for round $round",
              e,
            )
            None
          }
      })
      .map(_.flatten.toMap)

    // for calls that succeeded, get the round aggregates for the round from the scans that reported that they can serve it
    scansWithRounds.flatMap { scansWithRounds =>
      val roundsPerScan = scansWithRounds
        .map { case (s, r) => s.url -> r }
      logger.debug(
        s"Aggregate rounds per scan: ${roundsPerScan}, while getting round aggregate for round $round"
      )
      if (scansWithRounds.isEmpty) {
        Future.failed(
          ScanAggregator.CannotAdvance(
            s"No scans have any aggregated rounds available, while getting round aggregate for round $round"
          )
        )
      } else {
        val scansHavingRound = scansWithRounds
          .filter(_._2.contains(round))
          .keys
          .toSeq
        if (scansHavingRound.isEmpty) {
          Future.failed(
            ScanAggregator.CannotAdvance(
              s"No scans have aggregated round $round available, while getting round aggregate for round $round"
            )
          )
        } else {
          getRoundAggregatesWithinRound(scansHavingRound, round).map(Some(_))
        }
      }
    }
  }

  private def getRoundAggregatesWithinRound(
      scans: Seq[SingleScanConnection],
      round: Long,
  )(implicit
      tc: TraceContext
  ): Future[RoundAggregate] = {
    require(scans.nonEmpty, "No scans to get round aggregates from")
    // Adjust BFT to start off with total number of scans that have responded to have the aggregated round available
    val totalNumber = scans.size
    val f = (totalNumber - 1) / 3
    val nrTargetSuccess = f + 1

    def getRoundAggregateFromScan(
        scan: SingleScanConnection
    ): Future[ScanAggregator.RoundAggregate] = {
      logger
        .debug(
          s"Getting RoundAggregate for round $round from scan ${scan.url}"
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
          logger.info(
            s"Failed to get RoundAggregate for round $round from scan ${scan.url}",
            e,
          )
          Future.failed(e)
        }
    }
    // Use the BFT logic for getting matching nrTargetSuccess round aggregates
    BftScanConnection.executeCall(
      getRoundAggregateFromScan,
      scans,
      nrTargetSuccess,
      logger,
    )
  }
}
