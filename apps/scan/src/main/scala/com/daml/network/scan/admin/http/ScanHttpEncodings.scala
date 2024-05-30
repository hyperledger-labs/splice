package com.daml.network.scan.admin.http

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Identifier, TreeEvent}
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.http.v0.definitions
import com.daml.network.http.v0.definitions.UpdateHistoryItem
import com.google.protobuf.util.JsonFormat

import java.time.ZoneOffset
import scala.jdk.CollectionConverters.*

object ScanHttpEncodings {

  def ledgerTreeUpdateToHttp(
      tx: LedgerClient.GetTreeUpdatesResponse,
      migrationId: Long,
  ): UpdateHistoryItem = {
    val tree = tx.update match {
      case TransactionTreeUpdate(tree) => tree
      case ReassignmentUpdate(_) =>
        // TODO (#12552): deal with this case
        throw new IllegalStateException("Got an unexpected transfer.")
    }

    definitions.UpdateHistoryItem.fromUpdateHistoryTransaction(
      definitions
        .UpdateHistoryTransaction(
          tree.getUpdateId,
          migrationId,
          tree.getWorkflowId,
          tree.getRecordTime.toString,
          tx.domainId.toProtoPrimitive,
          tree.getEffectiveAt.toString,
          tree.getOffset,
          tree.getRootEventIds.asScala.toVector,
          tree.getEventsById.asScala.map { case (eventId, treeEvent) =>
            eventId -> treeEventToHttp(treeEvent)
          }.toMap,
        )
    )
  }

  private def treeEventToHttp(treeEvent: TreeEvent) = {
    treeEvent match {
      case event: CreatedEvent =>
        definitions.TreeEvent.fromCreatedEvent(
          definitions
            .CreatedEvent(
              "created_event",
              event.getEventId,
              event.getContractId,
              templateIdString(event.getTemplateId),
              event.getPackageName,
              // TODO (#12548): replace with the correct encoding
              io.circe.parser
                .parse(JsonFormat.printer().print(event.getArguments.toProto))
                .getOrElse(failedToWriteToJson()),
              event.getCreatedAt.atOffset(ZoneOffset.UTC),
            )
        )
      case event: ExercisedEvent =>
        definitions.TreeEvent.fromExercisedEvent(
          definitions
            .ExercisedEvent(
              "exercised_event",
              event.getEventId,
              event.getContractId,
              templateIdString(event.getTemplateId),
              event.getPackageName,
              event.getChoice,
              // TODO (#12548): replace with the correct encoding
              io.circe.parser
                .parse(
                  JsonFormat.printer().print(event.getChoiceArgument.toProto)
                )
                .getOrElse(failedToWriteToJson()),
              event.getChildEventIds.asScala.toVector,
              // TODO (#12548): replace with the correct encoding
              io.circe.parser
                .parse(
                  JsonFormat.printer().print(event.getExerciseResult.toProto)
                )
                .getOrElse(failedToWriteToJson()),
              event.isConsuming,
            )
        )
      case _ =>
        throw new IllegalStateException("Not a created or exercised event.")
    }
  }

  private def templateIdString(templateId: Identifier) =
    s"${templateId.getPackageId}:${templateId.getModuleName}:${templateId.getEntityName}"

  private def failedToWriteToJson() =
    throw new IllegalStateException("Failed to write to JSON.")

}
