// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.store

import com.daml.ledger.javaapi.data as javaApi
import com.daml.network.environment.ledger.api.{LedgerClient, TransactionTreeUpdate}
import com.daml.network.scan.admin.api.client.BackfillingScanConnection
import com.daml.network.store.HistoryBackfilling
import com.daml.network.store.HistoryBackfilling.{DomainRecordTimeRange, MigrationInfo, Outcome}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Backfills the scan history by copying data from a remote source history to the destination history.
  *
  * The algorithm caches the set of all remote connections, along with "static data" for the remove history.
  * Some of the data (such as the record time range) is technically not static because remote scans
  * could be backfilling themselves, but the algorithm is designed to terminate correctly as long as the cache
  * is eventually updated.
  */
class ScanHistoryBackfilling(
    createScanConnection: () => Future[BackfillingScanConnection],
    destinationHistory: HistoryBackfilling.DestinationHistory[LedgerClient.GetTreeUpdatesResponse],
    batchSize: Int = 100,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  import ScanHistoryBackfilling.*

  def backfill()(implicit tc: TraceContext): Future[Outcome] = {
    // This should periodically reset the state
    backfilling.backfill().recoverWith { case NonFatal(exception) =>
      logger.warn("Backfilling failed, resetting state")
      state.set(None)
      Future.failed(new RuntimeException("Backfilling failed", exception))
    }
  }

  // The state keeps track of all remote connections, along with "static data" for each migration id.
  private val state = new AtomicReference[Option[State]](None)

  /** Returns the current state which is guaranteed to have static data for the given migration id. */
  private def getState(migrationId: Long)(implicit tc: TraceContext): Future[State] = {
    state.get() match {
      case Some(s) =>
        if (s.migrationInfos.contains(migrationId))
          Future.successful(s)
        else
          for {
            migrationInfo <- s.connection.getMigrationInfo(migrationId)
            newState = State(s.connection, s.migrationInfos + (migrationId -> migrationInfo))
            _ = state.set(Some(newState))
          } yield newState
      case _ =>
        for {
          connection <- createScanConnection()
          migrationInfo <- connection.getMigrationInfo(migrationId)
          newState = State(connection, Map(migrationId -> migrationInfo))
          _ = state.set(Some(newState))
        } yield newState
    }
  }
  private def getMigrationInfo(
      migrationId: Long
  )(implicit tc: TraceContext): Future[MigrationInfo] =
    getState(migrationId)
      .map(
        _.migrationInfos
          .getOrElse(migrationId, throw new RuntimeException(s"No info for migration $migrationId"))
      )

  // Most queries return static or slowly-changing data, and should be answered from the cache.
  // The cache is updated on demand.
  private val sourceHistory =
    new HistoryBackfilling.SourceHistory[LedgerClient.GetTreeUpdatesResponse] {
      def isReady: Boolean = true

      def previousMigrationId(migrationId: Long)(implicit tc: TraceContext): Future[Option[Long]] =
        getMigrationInfo(migrationId).map(_.previousMigrationId)

      def recordTimeRange(migrationId: Long)(implicit
          tc: TraceContext
      ): Future[Map[DomainId, DomainRecordTimeRange]] =
        getMigrationInfo(migrationId).map(_.recordTimeRange)

      def items(
          migrationId: Long,
          domainId: DomainId,
          before: CantonTimestamp,
          count: Int,
      )(implicit tc: TraceContext): Future[Seq[LedgerClient.GetTreeUpdatesResponse]] = for {
        connection <- getState(migrationId).map(_.connection)
        items <- connection.getUpdatesBefore(migrationId, domainId, before, count)
      } yield items

      def isBackfillingComplete(migrationId: Long)(implicit tc: TraceContext): Future[Boolean] =
        getMigrationInfo(migrationId).map(_.complete)
    }

  private val backfilling =
    new HistoryBackfilling(destinationHistory, sourceHistory, batchSize = batchSize, loggerFactory)
}

object ScanHistoryBackfilling {
  case class State(
      // TODO(#14270): use connections from all scans (read from DsoRules contract)
      connection: BackfillingScanConnection,
      migrationInfos: Map[Long, MigrationInfo],
  )

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
