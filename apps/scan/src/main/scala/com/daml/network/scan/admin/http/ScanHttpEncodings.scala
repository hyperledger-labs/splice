// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.admin.http

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  DamlRecord,
  ExercisedEvent,
  Identifier,
  TransactionTree,
  TreeEvent,
  Value,
}
import com.daml.network.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.http.v0.definitions
import com.daml.network.http.v0.definitions.{UpdateHistoryItem, UpdateHistoryTransaction}
import com.daml.network.store.TreeUpdateWithMigrationId
import com.daml.network.util.Contract
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.topology.DomainId
import com.google.protobuf.ByteString
import io.circe.Json

import java.time.{Instant, ZoneOffset}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

sealed trait ScanHttpEncodings {

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

  private def treeEventToHttp(treeEvent: TreeEvent)(implicit
      elc: ErrorLoggingContext
  ) = {
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

  def httpToTxTreeUpdate(http: UpdateHistoryTransaction): TreeUpdateWithMigrationId =
    TreeUpdateWithMigrationId(
      LedgerClient.GetTreeUpdatesResponse(
        update = TransactionTreeUpdate(
          new TransactionTree(
            http.updateId,
            "",
            http.workflowId,
            Instant.parse(http.effectiveAt),
            http.offset,
            http.eventsById.map { case (eventId, treeEventHttp) =>
              eventId -> httpToTreeEvent(treeEventHttp)
            }.asJava,
            http.rootEventIds.asJava,
            http.synchronizerId,
            TraceContextOuterClass.TraceContext.getDefaultInstance,
            Instant.parse(http.recordTime),
          )
        ),
        domainId = DomainId.tryFromString(http.synchronizerId),
      ),
      http.migrationId,
    )

  def httpToTreeEvent(http: definitions.TreeEvent): TreeEvent = http match {
    case definitions.TreeEvent.members.CreatedEvent(createdHttp) => httpToCreatedEvent(createdHttp)
    case definitions.TreeEvent.members.ExercisedEvent(exercisedHttp) =>
      httpToExercisedEvent(exercisedHttp)
  }

  def httpToCreatedEvent(http: definitions.CreatedEvent): CreatedEvent =
    new CreatedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(),
      http.eventId,
      parseTemplateId(http.templateId),
      http.packageName,
      http.contractId,
      decodeContractPayload(http.createArguments),
      /*createdEventBlob = */ ByteString.EMPTY,
      /*interfaceViews = */ java.util.Collections.emptyMap(),
      /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
      /* contractKey = */ None.toJava,
      http.signatories.asJava,
      http.observers.asJava,
      http.createdAt.toInstant,
    )

  def httpToExercisedEvent(http: definitions.ExercisedEvent): ExercisedEvent =
    new ExercisedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(),
      http.eventId,
      parseTemplateId(http.templateId),
      http.packageName,
      http.interfaceId.map(parseTemplateId(_)).toJava,
      http.contractId,
      http.choice,
      decodeChoiceArgument(http.choiceArgument),
      http.actingParties.asJava,
      http.consuming,
      http.childEventIds.asJava,
      decodeExerciseResult(http.exerciseResult),
    )

  def createdEventToHttp(event: CreatedEvent)(implicit
      elc: ErrorLoggingContext
  ) = {
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

  private def parseTemplateId(templateId: String) = {
    val pattern = "(.*):(.*):(.*)".r
    val split = pattern
      .findFirstMatchIn(templateId)
      .getOrElse(
        throw new IllegalStateException(s"Cannot parse template Id $templateId")
      )
    val (packageId, moduleName, entityName) = (split.group(1), split.group(2), split.group(3))
    new Identifier(packageId, moduleName, entityName)
  }

  private def failedToWriteToJson(err: String): Nothing =
    throw new IllegalStateException(s"Failed to write to JSON: $err")

  /** Parses a string that is known to contain a valid JSON value to io.circe.Json */
  protected def tryParseJson(validJsonString: String): io.circe.Json =
    io.circe.parser
      .parse(validJsonString)
      .fold(err => failedToWriteToJson(err.message), identity)

  def encodeContractPayload(event: CreatedEvent)(implicit elc: ErrorLoggingContext): io.circe.Json

  def decodeContractPayload(json: io.circe.Json): DamlRecord

  def encodeChoiceArgument(event: ExercisedEvent)(implicit elc: ErrorLoggingContext): io.circe.Json

  def decodeChoiceArgument(json: io.circe.Json): Value

  def encodeExerciseResult(event: ExercisedEvent)(implicit elc: ErrorLoggingContext): io.circe.Json

  def decodeExerciseResult(json: io.circe.Json): Value

  protected def encodeValueFallback(
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

// A lossy, but much easier to process, encoding. Should be used for all endpoints not used for backfilling Scan.
case object LossyScanHttpEncodings extends ScanHttpEncodings {
  import com.daml.network.util.ValueJsonCodecCodegen
  override def encodeContractPayload(event: CreatedEvent)(implicit elc: ErrorLoggingContext): Json =
    ValueJsonCodecCodegen
      .serializableContractPayload(event)
      .fold(
        err => {
          elc.error(s"Failed to encode contract payload: $err")
          encodeValueFallback(err, event.getArguments)
        },
        tryParseJson,
      )

  override def encodeChoiceArgument(
      event: ExercisedEvent
  )(implicit elc: ErrorLoggingContext): Json =
    ValueJsonCodecCodegen
      .serializeChoiceArgument(event)
      .fold(
        err => {
          elc.error(s"Failed to encode choice argument: $err")
          encodeValueFallback(err, event.getChoiceArgument)
        },
        tryParseJson,
      )

  override def encodeExerciseResult(
      event: ExercisedEvent
  )(implicit elc: ErrorLoggingContext): Json =
    ValueJsonCodecCodegen
      .serializeChoiceResult(event)
      .fold(
        err => {
          elc.error(s"Failed to encode exercise result: $err")
          encodeValueFallback(err, event.getExerciseResult)
        },
        tryParseJson,
      )

  override def decodeContractPayload(json: Json): DamlRecord =
    throw new UnsupportedOperationException("Decoding the lossy codegen encoding is unsupported")

  override def decodeChoiceArgument(json: Json): Value =
    throw new UnsupportedOperationException("Decoding the lossy codegen encoding is unsupported")

  override def decodeExerciseResult(json: Json): Value =
    throw new UnsupportedOperationException("Decoding the lossy codegen encoding is unsupported")
}

// A lossless, but harder to process, encoding. Should be used only for backfilling Scan.
case object LosslessScanHttpEncodings extends ScanHttpEncodings {
  import com.daml.network.util.ValueJsonCodecProtobuf
  override def encodeContractPayload(event: CreatedEvent)(implicit elc: ErrorLoggingContext): Json =
    tryParseJson(
      ValueJsonCodecProtobuf
        .serializeValue(event.getArguments)
    )

  override def encodeChoiceArgument(
      event: ExercisedEvent
  )(implicit elc: ErrorLoggingContext): Json =
    tryParseJson(
      ValueJsonCodecProtobuf
        .serializeValue(event.getChoiceArgument)
    )

  override def encodeExerciseResult(
      event: ExercisedEvent
  )(implicit elc: ErrorLoggingContext): Json =
    tryParseJson(
      ValueJsonCodecProtobuf
        .serializeValue(event.getExerciseResult)
    )

  override def decodeContractPayload(json: Json): DamlRecord =
    ValueJsonCodecProtobuf.deserializeValue(json.toString()).asRecord().get()

  override def decodeChoiceArgument(json: Json): Value =
    ValueJsonCodecProtobuf.deserializeValue(json.toString())

  override def decodeExerciseResult(json: Json): Value =
    ValueJsonCodecProtobuf.deserializeValue(json.toString())
}
