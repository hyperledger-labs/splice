// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.admin.http

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Identifier, TreeEvent}
import com.daml.network.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import com.daml.network.environment.ledger.api.{ReassignmentUpdate, TransactionTreeUpdate}
import com.daml.network.http.v0.definitions
import com.daml.network.http.v0.definitions.UpdateHistoryItem
import com.daml.network.store.TreeUpdateWithMigrationId
import com.daml.network.util.{Contract, ValueJsonCodecCodegen}
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.logging.ErrorLoggingContext

import java.time.ZoneOffset
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object ScanHttpEncodings {

  def ledgerTreeUpdateToHttp(
      updateWithMigrationId: TreeUpdateWithMigrationId
  )(implicit elc: ErrorLoggingContext): UpdateHistoryItem = {

    updateWithMigrationId.update.update match {
      case TransactionTreeUpdate(tree) =>
        definitions.UpdateHistoryItem.fromUpdateHistoryTransaction(
          definitions
            .UpdateHistoryTransaction(
              tree.getUpdateId,
              updateWithMigrationId.migrationId,
              tree.getWorkflowId,
              tree.getRecordTime.toString,
              updateWithMigrationId.update.domainId.toProtoPrimitive,
              tree.getEffectiveAt.toString,
              tree.getOffset,
              tree.getRootEventIds.asScala.toVector,
              tree.getEventsById.asScala.map { case (eventId, treeEvent) =>
                eventId -> treeEventToHttp(treeEvent)
              }.toMap,
            )
        )
      case ReassignmentUpdate(update) =>
        update.event match {
          case Assign(submitter, source, target, unassignId, createdEvent, counter) =>
            definitions.UpdateHistoryItem.fromUpdateHistoryReassignment(
              definitions.UpdateHistoryReassignment(
                update.updateId,
                update.offset.getOffset,
                update.recordTime.toString,
                definitions.UpdateHistoryAssignment(
                  submitter.toProtoPrimitive,
                  source.toProtoPrimitive,
                  target.toProtoPrimitive,
                  updateWithMigrationId.migrationId,
                  unassignId,
                  createdEventToHttp(createdEvent),
                  counter,
                ),
              )
            )
          case Unassign(submitter, source, target, unassignId, contractId, counter) =>
            definitions.UpdateHistoryItem.fromUpdateHistoryReassignment(
              definitions.UpdateHistoryReassignment(
                update.updateId,
                update.offset.getOffset,
                update.recordTime.toString,
                definitions.UpdateHistoryUnassignment(
                  submitter.toProtoPrimitive,
                  source.toProtoPrimitive,
                  updateWithMigrationId.migrationId,
                  target.toProtoPrimitive,
                  unassignId,
                  counter,
                  contractId.contractId,
                ),
              )
            )
        }
    }
  }

  private def treeEventToHttp(treeEvent: TreeEvent)(implicit elc: ErrorLoggingContext) = {
    treeEvent match {
      case event: CreatedEvent =>
        definitions.TreeEvent.fromCreatedEvent(
          createdEventToHttp(event)
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
              encodeChoiceArgument(event),
              event.getChildEventIds.asScala.toVector,
              encodeExerciseResult(event),
              event.isConsuming,
              event.getActingParties.asScala.toVector,
              event.getInterfaceId.map(templateIdString(_)).toScala,
            )
        )
      case _ =>
        throw new IllegalStateException("Not a created or exercised event.")
    }
  }

  def createdEventToHttp(event: CreatedEvent)(implicit elc: ErrorLoggingContext) = {
    event.getContractKey.toScala.foreach { _ =>
      throw new IllegalStateException(
        "Contract keys are unexpected in UpdateHistory http encoded events"
      )
    }
    definitions
      .CreatedEvent(
        "created_event",
        event.getEventId,
        event.getContractId,
        templateIdString(event.getTemplateId),
        event.getPackageName,
        encodeContractPayload(event),
        event.getCreatedAt.atOffset(ZoneOffset.UTC),
        event.getSignatories.asScala.toVector,
        event.getObservers.asScala.toVector,
      )
  }

  private def templateIdString(templateId: Identifier) =
    s"${templateId.getPackageId}:${templateId.getModuleName}:${templateId.getEntityName}"

  private def failedToWriteToJson(err: String): Nothing =
    throw new IllegalStateException(s"Failed to write to JSON: $err")

  /** Parses a string that is known to contain a valid JSON value to io.circe.Json */
  private def tryParseJson(validJsonString: String): io.circe.Json =
    io.circe.parser
      .parse(validJsonString)
      .fold(err => failedToWriteToJson(err.message), identity)

  private def encodeContractPayload(
      event: CreatedEvent
  )(implicit elc: ErrorLoggingContext): io.circe.Json = {
    ValueJsonCodecCodegen
      .serializableContractPayload(event)
      .fold(
        err => {
          elc.error(s"Failed to encode contract payload: $err")
          encodeValueFallback(err, event.getArguments)
        },
        tryParseJson,
      )
  }

  private def encodeChoiceArgument(
      event: ExercisedEvent
  )(implicit elc: ErrorLoggingContext): io.circe.Json = {
    ValueJsonCodecCodegen
      .serializeChoiceArgument(event)
      .fold(
        err => {
          elc.error(s"Failed to encode choice argument: $err")
          encodeValueFallback(err, event.getChoiceArgument)
        },
        tryParseJson,
      )
  }

  private def encodeExerciseResult(
      event: ExercisedEvent
  )(implicit elc: ErrorLoggingContext): io.circe.Json = {
    ValueJsonCodecCodegen
      .serializeChoiceResult(event)
      .fold(
        err => {
          elc.error(s"Failed to encode exercise result: $err")
          encodeValueFallback(err, event.getExerciseResult)
        },
        tryParseJson,
      )
  }

  private def encodeValueFallback(
      error: String,
      value: com.daml.ledger.javaapi.data.Value,
  )(implicit elc: ErrorLoggingContext): io.circe.Json = {
    import io.circe.syntax.*
    val fallbackValue = tryParseJson(
      ApiCodecCompressed
        .apiValueToJsValue(Contract.javaValueToLfValue(value))
        .compactPrint
    )
    io.circe
      .JsonObject(
        "error" -> io.circe.Json.fromString(error),
        "value" -> fallbackValue,
      )
      .asJson
  }
}
