// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.config.{NetworkAppClientConfig, UpgradesConfig}
import com.daml.network.http.HttpClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.scan.store.ScanHistoryBackfilling.{
  FoundingTransactionTreeUpdate,
  InitialTransactionTreeUpdate,
  JoiningTransactionTreeUpdate,
}
import com.daml.network.scan.store.{ScanHistoryBackfilling, ScanStore}
import com.daml.network.store.{HistoryBackfilling, PageLimit, TreeUpdateWithMigrationId}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContextExecutor, Future, blocking}

class ScanHistoryBackfillingTrigger(
    store: ScanStore,
    remoteScanURL: Option[String],
    batchSize: Int,
    svParty: PartyId,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContextExecutor,
    override val tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[ScanHistoryBackfillingTrigger.Task] {

  private val currentMigrationId = store.updateHistory.domainMigrationInfo.currentMigrationId

  /** A cursor for iterating over the beginning of the update history in findHistoryStart,
    *  see [[com.daml.network.store.UpdateHistory.getUpdates()]].
    *  We need to store this as we don't want to start over from the beginning every time the trigger runs.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var findHistoryStartAfter: Option[(Long, CantonTimestamp)] = None

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var backfillingVar: Option[ScanHistoryBackfilling] = None

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ScanHistoryBackfillingTrigger.Task]] = {
    if (!store.updateHistory.isReady) {
      logger.debug("UpdateHistory is not yet ready")
      Future.successful(Seq.empty)
    } else {
      store.updateHistory.getBackfillingState().map {
        case Some(state) if state.complete =>
          Seq.empty
        case Some(_) =>
          Seq(ScanHistoryBackfillingTrigger.BackfillTask())
        case None =>
          Seq(ScanHistoryBackfillingTrigger.InitializeBackfillingTask(findHistoryStartAfter))
      }
    }
  }

  override protected def isStaleTask(task: ScanHistoryBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  override protected def completeTask(task: ScanHistoryBackfillingTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case ScanHistoryBackfillingTrigger.InitializeBackfillingTask(_) =>
      initializeBackfilling()
    case ScanHistoryBackfillingTrigger.BackfillTask() =>
      performBackfilling()
  }

  private def createScanConnection(url: String) = {
    implicit val tc: TraceContext = TraceContext.empty
    ScanConnection.singleUncached(
      ScanAppClientConfig(NetworkAppClientConfig(url = url)),
      UpgradesConfig(failOnVersionMismatch = false),
      context.clock,
      context.retryProvider,
      loggerFactory,
      // this is a polling trigger so just retry next time
      retryConnectionOnInitialFailure = false,
    )
  }

  private def initializeBackfillingFromUpdates(updates: Seq[TreeUpdateWithMigrationId])(implicit
      traceContext: TraceContext
  ) = {
    val initialUpdateO = updates.collectFirst(
      InitialTransactionTreeUpdate.fromTreeUpdate(
        dsoParty = store.key.dsoParty,
        svParty = svParty,
      )
    )
    for {
      result <- initialUpdateO match {
        case Some(FoundingTransactionTreeUpdate(treeUpdate, _)) =>
          for {
            _ <- store.updateHistory
              .initializeBackfilling(
                treeUpdate.migrationId,
                treeUpdate.update.domainId,
                treeUpdate.update.update.updateId,
                complete = true,
              )
          } yield TaskSuccess(
            s"Initialized backfilling from founding update ${treeUpdate.update.update.updateId}"
          )
        case Some(JoiningTransactionTreeUpdate(treeUpdate, _)) =>
          for {
            // Joining SVs need to delete updates before the joining transaction, because they ingested those updates
            // only with the visibility of the SV party and not the DSO party
            _ <- store.updateHistory.deleteUpdatesBefore(
              domainId = treeUpdate.update.domainId,
              migrationId = treeUpdate.migrationId,
              recordTime = treeUpdate.update.update.recordTime,
            )
            _ <- store.updateHistory
              .initializeBackfilling(
                treeUpdate.migrationId,
                treeUpdate.update.domainId,
                treeUpdate.update.update.updateId,
                complete = false,
              )
          } yield TaskSuccess(
            s"Initialized backfilling from joining update ${treeUpdate.update.update.updateId}"
          )
        case None =>
          Future.successful(
            TaskSuccess(
              s"No founding or joining transaction found until ${updates.lastOption.map(_.update.update.recordTime)}"
            )
          )
      }
    } yield result
  }

  private def initializeBackfilling()(implicit
      traceContext: TraceContext
  ): Future[TaskOutcome] = blocking {
    synchronized {
      val batchSize = 100
      for {
        updates <- store.updateHistory.getUpdates(
          findHistoryStartAfter,
          includeImportUpdates = false,
          PageLimit.tryCreate(batchSize),
        )
        _ = updates.lastOption.foreach(u =>
          findHistoryStartAfter = Some(u.migrationId -> u.update.update.recordTime)
        )
        result <-
          if (updates.isEmpty) {
            Future.successful(TaskNoop)
          } else {
            initializeBackfillingFromUpdates(updates)
          }
      } yield result
    }
  }

  private def getOrCreateBackfilling()(implicit
      traceContext: TraceContext
  ): Option[ScanHistoryBackfilling] = blocking {
    synchronized {
      backfillingVar match {
        case Some(backfilling) =>
          Some(backfilling)
        case None =>
          remoteScanURL match {
            case None =>
              // TODO(#14270): better treatment of this case - we hit this in all integration tests except for
              // ScanBackfillingIntegrationTest where we have a remoteScanURL configured
              logger.debug(
                "This scan is missing parts of the update history, but no remoteScanURL is configured."
              )
              None
            case Some(url) =>
              val backfilling =
                new ScanHistoryBackfilling(
                  createScanConnection = () => createScanConnection(url),
                  destinationHistory = store.updateHistory.destinationHistory,
                  currentMigrationId = currentMigrationId,
                  batchSize = batchSize,
                  loggerFactory = loggerFactory,
                  timeouts = context.timeouts,
                )
              backfillingVar = Some(backfilling)
              backfillingVar
          }
      }
    }
  }

  private def performBackfilling()(implicit traceContext: TraceContext): Future[TaskOutcome] = {
    val backfillingO = getOrCreateBackfilling()
    backfillingO match {
      case None =>
        // TODO(#14270): make this non-optional
        Future.successful(TaskNoop)
      case Some(backfilling) =>
        backfilling.backfill().map {
          case HistoryBackfilling.Outcome.MoreWorkAvailableNow =>
            TaskSuccess("Backfilling step completed")
          case HistoryBackfilling.Outcome.MoreWorkAvailableLater =>
            TaskNoop
          case HistoryBackfilling.Outcome.BackfillingIsComplete =>
            logger.info(
              "UpdateHistory backfilling is complete, this trigger should not do any work ever again"
            )
            TaskSuccess("Backfilling completed")
        }
    }
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super
      .closeAsync()
      .appended(
        SyncCloseable("backfilling", backfillingVar.foreach(_.close()))
      )
}

object ScanHistoryBackfillingTrigger {
  sealed trait Task extends PrettyPrinting
  final case class InitializeBackfillingTask(
      after: Option[(Long, CantonTimestamp)]
  ) extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("after", _.after))
  }
  final case class BackfillTask() extends Task {
    override def pretty: Pretty[this.type] =
      prettyOfClass()
  }
}
