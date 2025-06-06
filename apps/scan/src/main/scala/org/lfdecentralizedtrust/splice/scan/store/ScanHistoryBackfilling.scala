// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.daml.ledger.javaapi.data as javaApi
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BackfillingScanConnection
import org.lfdecentralizedtrust.splice.store.{
  HistoryBackfilling,
  ImportUpdatesBackfilling,
  TreeUpdateWithMigrationId,
}
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Backfills the scan history by copying data from a remote source history to the destination history.
  */
class ScanHistoryBackfilling(
    connection: BackfillingScanConnection,
    destinationHistory: HistoryBackfilling.DestinationHistory[UpdateHistoryResponse]
      with ImportUpdatesBackfilling.DestinationImportUpdates[UpdateHistoryResponse],
    currentMigrationId: Long,
    batchSize: Int = 100,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  private val sourceHistory =
    new HistoryBackfilling.SourceHistory[UpdateHistoryResponse]
      with ImportUpdatesBackfilling.SourceImportUpdates[UpdateHistoryResponse] {
      def isReady: Boolean = true

      override def migrationInfo(migrationId: Long)(implicit
          tc: TraceContext
      ): Future[Option[SourceMigrationInfo]] =
        connection.getMigrationInfo(migrationId)

      override def items(
          migrationId: Long,
          synchronizerId: SynchronizerId,
          before: CantonTimestamp,
          count: Int,
      )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] =
        connection.getUpdatesBefore(migrationId, synchronizerId, before, None, count)

      def importUpdates(
          migrationId: Long,
          afterUpdateId: String,
          count: Int,
      )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] =
        connection.getImportUpdates(migrationId, afterUpdateId, count)
    }

  private val backfilling =
    new HistoryBackfilling(
      destinationHistory,
      sourceHistory,
      currentMigrationId = currentMigrationId,
      batchSize = batchSize,
      loggerFactory,
    )

  private val importUpdatesBackfilling =
    new ImportUpdatesBackfilling(
      destinationHistory,
      sourceHistory,
      batchSize = batchSize,
      loggerFactory,
    )

  def backfill()(implicit tc: TraceContext): Future[HistoryBackfilling.Outcome] = {
    backfilling.backfill()
  }

  def backfillImportUpdates()(implicit
      tc: TraceContext
  ): Future[ImportUpdatesBackfilling.Outcome] = {
    importUpdatesBackfilling.backfillImportUpdates()
  }
}

object ScanHistoryBackfilling {
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
      treeUpdate: UpdateHistoryResponse,
      dsoParty: String,
  ): Boolean = {
    treeUpdate.update match {
      case TransactionTreeUpdate(tree) =>
        val rootEvents = tree.getRootNodeIds.asScala.map(tree.getEventsById.get)
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
      treeUpdate: UpdateHistoryResponse,
      svParty: String,
  ): Boolean = {
    treeUpdate.update match {
      case TransactionTreeUpdate(tree) =>
        val rootEvents = tree.getRootNodeIds.asScala.map(tree.getEventsById.get)
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
