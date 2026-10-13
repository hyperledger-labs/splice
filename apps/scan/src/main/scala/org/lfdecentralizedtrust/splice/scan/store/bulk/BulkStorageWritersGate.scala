// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageWritersGate.GateState
import org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling.BackfillingProgress
import org.lfdecentralizedtrust.splice.scan.store.historystart.{HistoryReadiness, HistoryStart}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

class BulkStorageWritersGate(
    readiness: HistoryReadiness,
    currentMigrationId: Long,
    peerBackfillingEnabled: Boolean,
    peerBackfillingProgress: BackfillingProgress,
    pollingInterval: FiniteDuration,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def updatesMayStart()(implicit tc: TraceContext): Future[GateState] =
    readiness.isHistoryBackfilled(currentMigrationId).flatMap {
      case true => peerCopyState()
      case false =>
        readiness.historyStart().flatMap {
          case None =>
            Future.successful(GateState.Closed("the history start of this Scan to be determined"))
          case Some(HistoryStart.Genesis) =>
            Future.successful(GateState.Closed("update history backfilling"))
          case Some(HistoryStart.From(_)) if !peerBackfillingEnabled =>
            Future.successful(
              GateState.Misconfigured(
                "the history of this Scan starts after genesis, so bulkStorage.backfilling.enabled must be set to copy the objects before its first own segment from peers"
              )
            )
          case Some(HistoryStart.From(_)) => peerCopyState()
        }
    }

  def snapshotsMayStart()(implicit tc: TraceContext): Future[GateState] =
    updatesMayStart().flatMap {
      case GateState.Open =>
        readiness.acsSnapshotsMayStart(currentMigrationId).map {
          case true => GateState.Open
          case false =>
            GateState.Closed("the ACS snapshot at the first own segment start to be seeded")
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

  private def peerCopyState()(implicit tc: TraceContext): Future[GateState] =
    if (!peerBackfillingEnabled) Future.successful(GateState.Open)
    else
      peerBackfillingProgress.isComplete.map {
        case true => GateState.Open
        case false => GateState.Closed("bulk storage backfilling from peers")
      }

  private def openWhen(writer: String, currentState: () => Future[GateState])(implicit
      tc: TraceContext
  ): Source[Boolean, Cancellable] =
    Source
      .tick(0.seconds, pollingInterval, ())
      .mapAsync(1)(_ => currentState())
      .statefulMap(() => Option.empty[GateState])(
        (previous, state) => {
          if (!previous.contains(state)) logChange(writer, previous, state)
          (Some(state), state)
        },
        _ => None,
      )
      .collect { case GateState.Open => true }
      .take(1)

  private def logChange(writer: String, previous: Option[GateState], state: GateState)(implicit
      tc: TraceContext
  ): Unit =
    state match {
      case GateState.Open =>
        if (previous.nonEmpty) logger.info(s"$writer starts")
      case GateState.Closed(waitingFor) =>
        logger.info(s"$writer waits for $waitingFor")
      case GateState.Misconfigured(problem) =>
        logger.error(s"$writer cannot start: $problem")
    }
}

object BulkStorageWritersGate {

  /** Whether the gate lets a bulk storage writer start. */
  sealed trait GateState extends Product with Serializable
  object GateState {

    /** The writer may start. */
    case object Open extends GateState

    /** The writer waits until `waitingFor` is done; the gate checks again on the next poll. */
    final case class Closed(waitingFor: String) extends GateState

    /** The configuration rules out starting; the gate stays closed until the configuration is fixed. */
    final case class Misconfigured(problem: String) extends GateState
  }
}
