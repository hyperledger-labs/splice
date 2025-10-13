// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, LifeCycle}
import org.lfdecentralizedtrust.splice.store.{
  HistoryBackfilling,
  HistoryMetrics,
  TxLogAppStore,
  TxLogBackfilling,
  UpdateHistory,
}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.TxLogBackfillingState
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState

import scala.concurrent.{ExecutionContext, Future}

class TxLogBackfillingTrigger[TXE](
    store: TxLogAppStore[TXE],
    updateHistory: UpdateHistory,
    batchSize: Int,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[TxLogBackfillingTrigger.Task] {

  private def party: PartyId = updateHistory.updateStreamParty

  override protected def extraMetricLabels = Seq(
    "party" -> party.toProtoPrimitive
  )

  private val currentMigrationId = updateHistory.domainMigrationInfo.currentMigrationId

  private val historyMetrics = new HistoryMetrics(context.metricsFactory)(
    MetricsContext.Empty
      .withExtraLabels(
        "current_migration_id" -> currentMigrationId.toString
      )
      .withExtraLabels(extraMetricLabels*)
  )
  private val backfilling = new TxLogBackfilling(
    store.multiDomainAcsStore,
    updateHistory,
    batchSize,
    context.loggerFactory,
  )

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[TxLogBackfillingTrigger.Task]] = {
    if (!updateHistory.isReady) {
      logger.debug("UpdateHistory is not yet ready")
      Future.successful(Seq.empty)
    } else if (!store.multiDomainAcsStore.destinationHistory.isReady) {
      logger.debug("MultiDomainAcsStore is not yet ready")
      Future.successful(Seq.empty)
    } else {
      for {
        sourceState <- updateHistory.getBackfillingState()
        destinationState <- store.multiDomainAcsStore.getTxLogBackfillingState()
      } yield {
        sourceState match {
          case BackfillingState.Complete =>
            destinationState match {
              case TxLogBackfillingState.Complete =>
                historyMetrics.TxLogBackfilling.completed.updateValue(1)
                Seq.empty
              case TxLogBackfillingState.InProgress =>
                Seq(TxLogBackfillingTrigger.BackfillTask(party))
              case TxLogBackfillingState.NotInitialized =>
                Seq(TxLogBackfillingTrigger.InitializeBackfillingTask(party))
            }
          case _ =>
            logger.debug("UpdateHistory is not yet complete")
            historyMetrics.TxLogBackfilling.completed.updateValue(0)
            Seq.empty
        }
      }
    }
  }

  override protected def isStaleTask(task: TxLogBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override protected def completeTask(task: TxLogBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case TxLogBackfillingTrigger.BackfillTask(_) =>
      performBackfilling()
    case TxLogBackfillingTrigger.InitializeBackfillingTask(_) =>
      initializeBackfilling()
  }

  private def initializeBackfilling()(implicit
      traceContext: TraceContext
  ): Future[TaskOutcome] = {
    logger.info("Initializing backfilling")
    store.multiDomainAcsStore.initializeTxLogBackfilling().map { _ =>
      TaskSuccess("Backfilling initialized")
    }
  }

  private def performBackfilling()(implicit traceContext: TraceContext): Future[TaskOutcome] = for {
    outcome <- backfilling.backfill().map {
      case HistoryBackfilling.Outcome.MoreWorkAvailableNow(workDone) =>
        historyMetrics.TxLogBackfilling.completed.updateValue(0)
        // Using MetricsContext.Empty is okay, because it's merged with the StoreMetrics context
        historyMetrics.TxLogBackfilling.latestRecordTime.updateValue(
          workDone.lastBackfilledRecordTime.toMicros
        )(MetricsContext.Empty)
        historyMetrics.TxLogBackfilling.updateCount.inc(
          workDone.backfilledUpdates
        )(MetricsContext.Empty)
        historyMetrics.TxLogBackfilling.eventCount.inc(workDone.backfilledEvents)(
          MetricsContext.Empty
        )
        TaskSuccess("Backfilling step completed")
      case HistoryBackfilling.Outcome.MoreWorkAvailableLater =>
        historyMetrics.TxLogBackfilling.completed.updateValue(0)
        TaskNoop
      case HistoryBackfilling.Outcome.BackfillingIsComplete =>
        historyMetrics.TxLogBackfilling.completed.updateValue(1)
        logger.info(
          "TxLog backfilling is complete, this trigger should not do any work ever again"
        )
        TaskSuccess("Backfilling completed")
    }
  } yield outcome

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    LifeCycle.close(historyMetrics)(logger)
    super.closeAsync()
  }
}

object TxLogBackfillingTrigger {
  sealed trait Task extends PrettyPrinting
  final case class BackfillTask(party: PartyId) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("party", _.party)
      )
  }
  final case class InitializeBackfillingTask(party: PartyId) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("party", _.party)
      )
  }
}
