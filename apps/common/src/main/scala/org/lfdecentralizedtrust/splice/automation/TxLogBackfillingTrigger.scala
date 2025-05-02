// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.store.{
  HistoryBackfilling,
  HistoryMetrics,
  TxLogAppStore,
  TxLogBackfilling,
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
    batchSize: Int,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[ScanHistoryBackfillingTrigger.Task] {

  private def party: PartyId = store.updateHistory.updateStreamParty

  override protected def extraMetricLabels = Seq(
    "party" -> party.toProtoPrimitive
  )

  private val currentMigrationId = store.updateHistory.domainMigrationInfo.currentMigrationId

  private val historyMetrics = new HistoryMetrics(context.metricsFactory)(
    MetricsContext.Empty
      .withExtraLabels(
        "current_migration_id" -> currentMigrationId.toString
      )
      .withExtraLabels(extraMetricLabels*)
  )
  private val backfilling = new TxLogBackfilling(
    store.multiDomainAcsStore,
    store.updateHistory,
    batchSize,
    context.loggerFactory,
  )

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ScanHistoryBackfillingTrigger.Task]] = {
    if (!store.updateHistory.isReady) {
      logger.debug("UpdateHistory is not yet ready")
      Future.successful(Seq.empty)
    } else if (!store.multiDomainAcsStore.destinationHistory.isReady) {
      logger.debug("MultiDomainAcsStore is not yet ready")
      Future.successful(Seq.empty)
    } else {
      for {
        sourceState <- store.updateHistory.getBackfillingState()
        destinationState <- store.multiDomainAcsStore.getTxLogBackfillingState()
      } yield {
        sourceState match {
          case BackfillingState.Complete =>
            destinationState match {
              case TxLogBackfillingState.Complete =>
                historyMetrics.TxLogBackfilling.completed.updateValue(1)
                Seq.empty
              case TxLogBackfillingState.InProgress =>
                Seq(ScanHistoryBackfillingTrigger.BackfillTask(party))
              case TxLogBackfillingState.NotInitialized =>
                Seq(ScanHistoryBackfillingTrigger.InitializeBackfillingTask(party))
            }
          case _ =>
            logger.debug("UpdateHistory is not yet complete")
            historyMetrics.TxLogBackfilling.completed.updateValue(0)
            Seq.empty
        }
      }
    }
  }

  override protected def isStaleTask(task: ScanHistoryBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override protected def completeTask(task: ScanHistoryBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case ScanHistoryBackfillingTrigger.BackfillTask(_) =>
      performBackfilling()
    case ScanHistoryBackfillingTrigger.InitializeBackfillingTask(_) =>
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
        historyMetrics.UpdateHistoryBackfilling.completed.updateValue(0)
        TaskNoop
      case HistoryBackfilling.Outcome.BackfillingIsComplete =>
        historyMetrics.UpdateHistoryBackfilling.completed.updateValue(1)
        logger.info(
          "UpdateHistory backfilling is complete, this trigger should not do any work ever again"
        )
        TaskSuccess("Backfilling completed")
    }
  } yield outcome

}

object ScanHistoryBackfillingTrigger {
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
