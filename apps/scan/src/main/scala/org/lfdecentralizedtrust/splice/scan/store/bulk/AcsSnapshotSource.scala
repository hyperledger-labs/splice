// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.{Attributes, Outlet, SourceShape}
import org.apache.pekko.stream.stage.{AsyncCallback, GraphStage, GraphStageLogic, OutHandler}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotResult
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

case class AcsSnapshotSource(
    acsSnapshotStore: AcsSnapshotStore,
    timestamp: CantonTimestamp,
    migrationId: Long,
    override val loggerFactory: NamedLoggerFactory,
)(implicit tc: TraceContext, ec: ExecutionContext)
    extends GraphStage[SourceShape[Vector[SpliceCreatedEvent]]]
    with NamedLogging {
  val out: Outlet[Vector[SpliceCreatedEvent]] = Outlet("AcsSnapshotSource")
  override def shape: SourceShape[Vector[SpliceCreatedEvent]] = SourceShape(out)

  val numUpdatesPerQuery = 1000

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) with OutHandler {
      val token = new AtomicReference[Option[Long]](None)

      val asyncCallback: AsyncCallback[QueryAcsSnapshotResult] = getAsyncCallback {
        case result: QueryAcsSnapshotResult =>
          if (result.createdEventsInPage.isEmpty) {
            complete(out)
            token.set(None)
          } else {
            push(out, result.createdEventsInPage)
            token.set(result.afterToken)
          }
        case _ =>
          logger.error("asyncCallback unexpectedly called with an error")
      }

      val failureCallback: AsyncCallback[Throwable] = getAsyncCallback { ex =>
        fail(out, ex)
      }

      override def onPull(): Unit = {
        acsSnapshotStore
          .queryAcsSnapshot(
            migrationId,
            timestamp,
            token.get(),
            PageLimit.tryCreate(numUpdatesPerQuery),
            Seq.empty,
            Seq.empty,
          )
          .onComplete {
            case Success(value) => asyncCallback.invoke(value)
            case Failure(exception) => failureCallback.invoke(exception)
          }
      }
      setHandler(out, this)
    }
  }

}
