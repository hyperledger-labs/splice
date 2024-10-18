// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.daml.ledger.javaapi.data as javaApi
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import org.lfdecentralizedtrust.splice.environment.ledger.api.{LedgerClient, TransactionTreeUpdate}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BackfillingScanConnection
import org.lfdecentralizedtrust.splice.store.{HistoryBackfilling, TreeUpdateWithMigrationId}
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.{Outcome, SourceMigrationInfo}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  Lifecycle,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Backfills the scan history by copying data from a remote source history to the destination history.
  *
  * The algorithm caches the set of all remote connections, along with "static data" for the remote history.
  * Some of the data (such as the record time range) is technically not static because remote scans
  * could be backfilling themselves, but the algorithm is designed to terminate correctly as long as the cache
  * is eventually updated.
  */
class ScanHistoryBackfilling(
    createScanConnection: () => Future[BackfillingScanConnection],
    destinationHistory: HistoryBackfilling.DestinationHistory[LedgerClient.GetTreeUpdatesResponse],
    currentMigrationId: Long,
    batchSize: Int = 100,
    override val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
    metricsFactory: LabeledMetricsFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends FlagCloseableAsync
    with NamedLogging {

  import ScanHistoryBackfilling.*

  // The state keeps track of all remote connections, along with "static data" for each migration id.
  private val state = new AtomicReference[Option[State]](None)

  // Most `sourceHistory` queries return static or slowly-changing data, and should be answered from the cache.
  // The cache is updated on demand.
  private val sourceHistory =
    new HistoryBackfilling.SourceHistory[LedgerClient.GetTreeUpdatesResponse] {
      def isReady: Boolean = true

      def migrationInfo(migrationId: Long)(implicit
          tc: TraceContext
      ): Future[Option[SourceMigrationInfo]] =
        getMigrationInfo(migrationId)

      def items(
          migrationId: Long,
          domainId: DomainId,
          before: CantonTimestamp,
          count: Int,
      )(implicit tc: TraceContext): Future[Seq[LedgerClient.GetTreeUpdatesResponse]] = for {
        connection <- getState(migrationId).map(_.connection)
        items <- connection.getUpdatesBefore(migrationId, domainId, before, count)
      } yield items
    }

  private val backfilling =
    new HistoryBackfilling(
      destinationHistory,
      sourceHistory,
      currentMigrationId = currentMigrationId,
      batchSize = batchSize,
      loggerFactory,
      metricsFactory,
    )

  def backfill()(implicit tc: TraceContext): Future[Outcome] = {
    // TODO(#14270): This should periodically reset the state
    backfilling.backfill().recoverWith { case NonFatal(exception) =>
      logger.warn("Backfilling failed, resetting state")
      state.set(None)
      Future.failed(new RuntimeException("Backfilling failed", exception))
    }
  }

  /** Returns the current state which is guaranteed to have static data for the given migration id. */
  private def getState(migrationId: Long)(implicit tc: TraceContext): Future[State] = {
    state.get() match {
      case Some(s) =>
        if (s.migrationInfos.contains(migrationId))
          Future.successful(s)
        else
          for {
            migrationInfo <- s.connection.getMigrationInfo(migrationId)
            newState = s.withMigrationInfo(migrationId, migrationInfo)
            _ = state.set(Some(newState))
          } yield newState
      case _ =>
        for {
          connection <- createScanConnection()
          migrationInfo <- connection.getMigrationInfo(migrationId)
          newState = State(connection, Map.empty, logger)
            .withMigrationInfo(migrationId, migrationInfo)
          _ = state.set(Some(newState))
        } yield newState
    }
  }
  private def getMigrationInfo(
      migrationId: Long
  )(implicit tc: TraceContext): Future[Option[SourceMigrationInfo]] =
    getState(migrationId)
      .map(_.migrationInfos.get(migrationId))

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
    SyncCloseable("close state", state.getAndSet(None).foreach(_.close()))
  )
}

object ScanHistoryBackfilling {
  case class State(
      // TODO(#14270): use connections from all scans (read from DsoRules contract)
      connection: BackfillingScanConnection,
      migrationInfos: Map[Long, SourceMigrationInfo],
      logger: TracedLogger,
  ) extends AutoCloseable {
    def withMigrationInfo(migrationId: Long, migrationInfo: Option[SourceMigrationInfo]): State =
      copy(migrationInfos = migrationInfos.updatedWith(migrationId)(_ => migrationInfo))

    override def close(): Unit = {
      Lifecycle.close(connection)(logger)
    }
  }

  sealed trait InitialTransactionTreeUpdate
  object InitialTransactionTreeUpdate {
    def fromTreeUpdate(
        dsoParty: PartyId,
        svParty: PartyId,
    ): PartialFunction[TreeUpdateWithMigrationId, InitialTransactionTreeUpdate] = {
      case treeUpdate
          if (isFoundingTransactionTreeUpdate(treeUpdate.update, dsoParty.toProtoPrimitive)) =>
        FoundingTransactionTreeUpdate(treeUpdate, dsoParty)
      case treeUpdate
          if (isJoiningTransactionTreeUpdate(treeUpdate.update, svParty.toProtoPrimitive)) =>
        JoiningTransactionTreeUpdate(treeUpdate, svParty)
    }
  }
  final case class FoundingTransactionTreeUpdate(
      treeUpdate: TreeUpdateWithMigrationId,
      dsoParty: PartyId,
  ) extends InitialTransactionTreeUpdate
  final case class JoiningTransactionTreeUpdate(
      treeUpdate: TreeUpdateWithMigrationId,
      svParty: PartyId,
  ) extends InitialTransactionTreeUpdate

  /** Returns whether the given update is the first update in the network.
    *
    * Note: The first transaction in the network is submitted by the founding SV node, and contains two root events:
    * A create event for the `DsoBootstrap` contract, and an exercise event for the `DsoBootstrap_Bootstrap` choice.
    */
  def isFoundingTransactionTreeUpdate(
      treeUpdate: LedgerClient.GetTreeUpdatesResponse,
      dsoParty: String,
  ): Boolean = {
    treeUpdate.update match {
      case TransactionTreeUpdate(tree) =>
        val rootEvents = tree.getRootEventIds.asScala.map(tree.getEventsById.get)
        rootEvents.exists {
          case created: javaApi.CreatedEvent =>
            // In `template DsoBootstrap`, the first argument is the DSO party
            val dsoPartyField = for {
              firstField <- created.getArguments.getFields.asScala.headOption
              fieldPartyValue <- firstField.getValue.asParty().toScala
            } yield fieldPartyValue.getValue
            created.getTemplateId.getModuleName == "Splice.DsoBootstrap" &&
            created.getTemplateId.getEntityName == "DsoBootstrap" &&
            dsoPartyField.contains(dsoParty)
          case _ => false
        }
      case _ => false
    }
  }

  /** Returns whether the given update is the first update where the given SV is part of the DSO.
    * I.e., for this and all future updates, the participant of the given SV will see the same projection of the update
    * as all other members of the DSO.
    *
    * Note: when a new SV joins the network, its party is initially not part of the DSO. During onboarding, the DSO submits
    * a transaction that exercises the `DsoRules_StartSvOnboarding` choice on the `DsoBootstrap` contract, which creates an
    * onboarding request contract. At this point, the DSO sees the whole transaction, while the new SV party only sees the create
    * event for the onboarding request contract.
    * After that, the new SV party is added to the DSO through topology transactions, and by the time the
    * `DsoRules_AddConfirmedSv` choice is exercised, the new SV party is guaranteed to be part of the DSO.
    *
    * Note that due to constant activity on the network, there might be a few transactions submitted before
    * `DsoRules_AddConfirmedSv` where the new SV is already part of the DSO.
    */
  def isJoiningTransactionTreeUpdate(
      treeUpdate: LedgerClient.GetTreeUpdatesResponse,
      svParty: String,
  ): Boolean = {
    treeUpdate.update match {
      case TransactionTreeUpdate(tree) =>
        val rootEvents = tree.getRootEventIds.asScala.map(tree.getEventsById.get)
        rootEvents.exists {
          case exercised: javaApi.ExercisedEvent =>
            // In `choice DsoRules_AddConfirmedSv`, the first argument is the new SV party
            val svPartyField = for {
              argument <- exercised.getChoiceArgument.asRecord().toScala
              firstField <- argument.getFields.asScala.headOption
              fieldPartyValue <- firstField.getValue.asParty().toScala
            } yield fieldPartyValue.getValue
            exercised.getChoice == "DsoRules_AddConfirmedSv" &&
            exercised.getTemplateId.getModuleName == "Splice.DsoRules" &&
            exercised.getTemplateId.getEntityName == "DsoRules" &&
            svPartyField.contains(svParty)
          case _ => false
        }
      case _ => false
    }
  }
}
