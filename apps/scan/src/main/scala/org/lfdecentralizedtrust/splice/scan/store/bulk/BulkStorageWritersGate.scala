// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageWritersGate.Decision
import org.lfdecentralizedtrust.splice.scan.store.historystart.{HistoryReadiness, HistoryStart}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

class BulkStorageWritersGate(
    readiness: HistoryReadiness,
    currentMigrationId: Long,
    peerCopyComplete: Option[TraceContext => Future[Boolean]],
    pollingInterval: FiniteDuration,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def updatesMayStart()(implicit tc: TraceContext): Future[Decision] =
    readiness.isHistoryBackfilled(currentMigrationId).flatMap {
      case true => peerCopyDecision()
      case false =>
        readiness.historyStart().flatMap {
          case None =>
            Future.successful(Decision.Waiting("the history start of this Scan to be determined"))
          case Some(HistoryStart.Genesis) =>
            Future.successful(Decision.Waiting("update history backfilling"))
          case Some(HistoryStart.From(_)) if peerCopyComplete.isEmpty =>
            Future.successful(
              Decision.Misconfigured(
                "the history of this Scan starts after genesis, so bulkStorage.backfilling.enabled must be set to copy the objects before its first own segment from peers"
              )
            )
          case Some(HistoryStart.From(_)) => peerCopyDecision()
        }
    }

  def snapshotsMayStart()(implicit tc: TraceContext): Future[Decision] =
    updatesMayStart().flatMap {
      case Decision.Open =>
        readiness.acsSnapshotsMayStart(currentMigrationId).map {
          case true => Decision.Open
          case false =>
            Decision.Waiting("the ACS snapshot at the first own segment start to be seeded")
        }
      case shut => Future.successful(shut)
    }

  def whenUpdatesMayStart(writer: String)(implicit
      tc: TraceContext
  ): Source[Boolean, Cancellable] =
    openWhen(writer, () => updatesMayStart())

  def whenSnapshotsMayStart(writer: String)(implicit
      tc: TraceContext
  ): Source[Boolean, Cancellable] =
    openWhen(writer, () => snapshotsMayStart())

  private def peerCopyDecision()(implicit tc: TraceContext): Future[Decision] =
    peerCopyComplete match {
      case None => Future.successful(Decision.Open)
      case Some(isComplete) =>
        isComplete(tc).map {
          case true => Decision.Open
          case false => Decision.Waiting("bulk storage backfilling from peers")
        }
    }

  private def openWhen(writer: String, decide: () => Future[Decision])(implicit
      tc: TraceContext
  ): Source[Boolean, Cancellable] =
    Source
      .tick(0.seconds, pollingInterval, ())
      .mapAsync(1)(_ => decide())
      .statefulMap(() => Option.empty[Decision])(
        (previous, decision) => {
          if (!previous.contains(decision)) logChange(writer, previous, decision)
          (Some(decision), decision)
        },
        _ => None,
      )
      .collect { case Decision.Open => true }
      .take(1)

  private def logChange(writer: String, previous: Option[Decision], decision: Decision)(implicit
      tc: TraceContext
  ): Unit =
    decision match {
      case Decision.Open =>
        if (previous.nonEmpty) logger.info(s"$writer starts")
      case Decision.Waiting(on) =>
        logger.info(s"$writer waits for $on")
      case Decision.Misconfigured(problem) =>
        logger.error(s"$writer cannot start: $problem")
    }
}

object BulkStorageWritersGate {
  sealed trait Decision extends Product with Serializable
  object Decision {
    case object Open extends Decision
    final case class Waiting(on: String) extends Decision
    final case class Misconfigured(problem: String) extends Decision
  }
}
