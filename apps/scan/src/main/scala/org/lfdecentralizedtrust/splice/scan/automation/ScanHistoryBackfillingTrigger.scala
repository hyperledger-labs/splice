// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerClient
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{
  BackfillingScanConnection,
  BftScanConnection,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.scan.store.ScanHistoryBackfilling.{
  FoundingTransactionTreeUpdate,
  InitialTransactionTreeUpdate,
  JoiningTransactionTreeUpdate,
}
import org.lfdecentralizedtrust.splice.scan.store.{ScanHistoryBackfilling, ScanStore}
import org.lfdecentralizedtrust.splice.store.{
  HistoryBackfilling,
  PageLimit,
  TreeUpdateWithMigrationId,
}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
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
    svName: String,
    ledgerClient: SpliceLedgerClient,
    batchSize: Int,
    svParty: PartyId,
    upgradesConfig: UpgradesConfig,
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
    *  see [[org.lfdecentralizedtrust.splice.store.UpdateHistory.getUpdates()]].
    *  We need to store this as we don't want to start over from the beginning every time the trigger runs.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var findHistoryStartAfter: Option[(Long, CantonTimestamp)] = None

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var connectionVar: Option[BftScanConnection] = None

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
                treeUpdate.update.synchronizerId,
                treeUpdate.update.update.updateId,
                complete = true,
              )
          } yield TaskSuccess(
            s"Initialized backfilling from founding update ${treeUpdate.update.update.updateId}"
          )
        case Some(JoiningTransactionTreeUpdate(treeUpdate, _)) =>
          for {
            // Before deleting updates, we need to delete ACS snapshots that were generated before backfilling was enabled.
            // This will delete all ACS snapshots for migration id where the SV node joined the network.
            _ <- store.updateHistory.deleteAcsSnapshotsAfter(
              historyId = store.updateHistory.historyId,
              migrationId = treeUpdate.migrationId,
              recordTime = CantonTimestamp.MinValue,
            )
            // Joining SVs need to delete updates before the joining transaction, because they ingested those updates
            // only with the visibility of the SV party and not the DSO party.
            // Note that this will also delete the import updates because they have a record time of 0,
            // which is good because we want to remove them.
            _ <- store.updateHistory.deleteUpdatesBefore(
              synchronizerId = treeUpdate.update.synchronizerId,
              migrationId = treeUpdate.migrationId,
              recordTime = treeUpdate.update.update.recordTime,
            )
            _ <- store.updateHistory
              .initializeBackfilling(
                treeUpdate.migrationId,
                treeUpdate.update.synchronizerId,
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

  private def getOrCreateScanConnection()(implicit tc: TraceContext): Future[BftScanConnection] =
    blocking {
      synchronized {
        connectionVar match {
          case Some(connection) =>
            Future.successful(connection)
          case None =>
            for {
              connection <- BftScanConnection.peerScanConnection(
                store,
                svName,
                ledgerClient,
                // When the network is starting up, the pool of SVs is changing fast
                // Using a short refresh interval to quickly pick up new SVs
                scansRefreshInterval = context.config.pollingInterval,
                amuletRulesCacheTimeToLive = ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
                upgradesConfig,
                context.clock,
                context.retryProvider,
                loggerFactory,
              )
            } yield {
              connectionVar = Some(connection)
              connection
            }
        }
      }
    }

  private def getOrCreateBackfilling(
      connection: BackfillingScanConnection
  ): ScanHistoryBackfilling = blocking {
    synchronized {
      backfillingVar match {
        case Some(backfilling) =>
          backfilling
        case None =>
          val backfilling =
            new ScanHistoryBackfilling(
              connection = connection,
              destinationHistory = store.updateHistory.destinationHistory,
              currentMigrationId = currentMigrationId,
              batchSize = batchSize,
              loggerFactory = loggerFactory,
              metricsFactory = context.metricsFactory,
            )
          backfillingVar = Some(backfilling)
          backfilling
      }
    }
  }

  private def performBackfilling()(implicit traceContext: TraceContext): Future[TaskOutcome] = for {
    connection <- getOrCreateScanConnection()
    backfilling = getOrCreateBackfilling(connection)
    outcome <- backfilling.backfill().map {
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
  } yield outcome

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    connectionVar
      .map(connection =>
        SyncCloseable(
          "closing scan connection",
          connection.close(),
        )
      )
      .toList
  }
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
