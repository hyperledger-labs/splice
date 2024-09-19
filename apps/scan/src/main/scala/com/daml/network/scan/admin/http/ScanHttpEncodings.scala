// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.admin.http

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data as javaApi
import com.daml.network.environment.ledger.api as ledgerApi
import com.daml.network.http.v0.definitions as httpApi
import com.daml.network.store.TreeUpdateWithMigrationId
import com.daml.network.util.Contract
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.google.protobuf.ByteString
import io.circe.Json

import java.time.{Instant, ZoneOffset}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Transcodes between different representations of ledger updates:
  *
  * lapi: com.daml.network.environment.ledger.api.*
  * java: com.daml.ledger.javaapi.data.*
  * http: com.daml.network.http.v0.httpApi.*
  */
sealed trait ScanHttpEncodings {

  def lapiToHttpUpdate(
      updateWithMigrationId: TreeUpdateWithMigrationId
  )(implicit elc: ErrorLoggingContext): httpApi.UpdateHistoryItem = {

    updateWithMigrationId.update.update match {
      case ledgerApi.TransactionTreeUpdate(tree) =>
        httpApi.UpdateHistoryItem.fromUpdateHistoryTransaction(
          httpApi
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
                eventId -> javaToHttpEvent(treeEvent)
              }.toMap,
            )
        )
      case ledgerApi.ReassignmentUpdate(update) =>
        update.event match {
          case ledgerApi.ReassignmentEvent.Assign(
                submitter,
                source,
                target,
                unassignId,
                createdEvent,
                counter,
              ) =>
            httpApi.UpdateHistoryItem.fromUpdateHistoryReassignment(
              httpApi.UpdateHistoryReassignment(
                update.updateId,
                update.offset.getOffset,
                update.recordTime.toString,
                httpApi.UpdateHistoryAssignment(
                  submitter.toProtoPrimitive,
                  source.toProtoPrimitive,
                  target.toProtoPrimitive,
                  updateWithMigrationId.migrationId,
                  unassignId,
                  javaToHttpCreatedEvent(createdEvent),
                  counter,
                ),
              )
            )
          case ledgerApi.ReassignmentEvent.Unassign(
                submitter,
                source,
                target,
                unassignId,
                contractId,
                counter,
              ) =>
            httpApi.UpdateHistoryItem.fromUpdateHistoryReassignment(
              httpApi.UpdateHistoryReassignment(
                update.updateId,
                update.offset.getOffset,
                update.recordTime.toString,
                httpApi.UpdateHistoryUnassignment(
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

  private def javaToHttpEvent(treeEvent: javaApi.TreeEvent)(implicit
      elc: ErrorLoggingContext
  ): httpApi.TreeEvent = {
    treeEvent match {
      case event: javaApi.CreatedEvent =>
        httpApi.TreeEvent.fromCreatedEvent(
          javaToHttpCreatedEvent(event)
        )
      case event: javaApi.ExercisedEvent =>
        httpApi.TreeEvent.fromExercisedEvent(
          httpApi
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

  def javaToHttpCreatedEvent(event: javaApi.CreatedEvent)(implicit
      elc: ErrorLoggingContext
  ): httpApi.CreatedEvent = {
    event.getContractKey.toScala.foreach { _ =>
      throw new IllegalStateException(
        "Contract keys are unexpected in UpdateHistory http encoded events"
      )
    }
    httpApi
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

  def httpToLapiUpdate(http: httpApi.UpdateHistoryItem): TreeUpdateWithMigrationId = http match {
    case httpApi.UpdateHistoryItem.members.UpdateHistoryTransaction(httpTransaction) =>
      httpToLapiTransaction(httpTransaction)
    case httpApi.UpdateHistoryItem.members.UpdateHistoryReassignment(httpReassignment) =>
      httpToLapiReassignment(httpReassignment)
  }

  def httpToLapiTransaction(http: httpApi.UpdateHistoryTransaction): TreeUpdateWithMigrationId =
    TreeUpdateWithMigrationId(
      ledgerApi.LedgerClient.GetTreeUpdatesResponse(
        update = ledgerApi.TransactionTreeUpdate(
          new javaApi.TransactionTree(
            http.updateId,
            "",
            http.workflowId,
            Instant.parse(http.effectiveAt),
            http.offset,
            http.eventsById.map { case (eventId, treeEventHttp) =>
              eventId -> httpToJavaEvent(treeEventHttp)
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

  def httpToLapiReassignment(http: httpApi.UpdateHistoryReassignment): TreeUpdateWithMigrationId =
    http.event match {
      case httpApi.UpdateHistoryReassignment.Event.members.UpdateHistoryAssignment(assignment) =>
        TreeUpdateWithMigrationId(
          ledgerApi.LedgerClient.GetTreeUpdatesResponse(
            update = ledgerApi.ReassignmentUpdate(
              transfer = ledgerApi.Reassignment(
                updateId = http.updateId,
                offset = new javaApi.ParticipantOffset.Absolute(http.offset),
                recordTime = CantonTimestamp.assertFromInstant(Instant.parse(http.recordTime)),
                event = ledgerApi.ReassignmentEvent.Assign(
                  submitter = PartyId.tryFromProtoPrimitive(assignment.submitter),
                  source = DomainId.tryFromString(assignment.sourceSynchronizer),
                  target = DomainId.tryFromString(assignment.targetSynchronizer),
                  unassignId = assignment.unassignId,
                  createdEvent = httpToJavaCreatedEvent(assignment.createdEvent),
                  counter = assignment.reassignmentCounter,
                ),
              )
            ),
            domainId = DomainId.tryFromString(assignment.targetSynchronizer),
          ),
          assignment.migrationId,
        )
      case httpApi.UpdateHistoryReassignment.Event.members
            .UpdateHistoryUnassignment(unassignment) =>
        TreeUpdateWithMigrationId(
          ledgerApi.LedgerClient.GetTreeUpdatesResponse(
            update = ledgerApi.ReassignmentUpdate(
              transfer = ledgerApi.Reassignment(
                updateId = http.updateId,
                offset = new javaApi.ParticipantOffset.Absolute(http.offset),
                recordTime = CantonTimestamp.assertFromInstant(Instant.parse(http.recordTime)),
                event = ledgerApi.ReassignmentEvent.Unassign(
                  submitter = PartyId.tryFromProtoPrimitive(unassignment.submitter),
                  source = DomainId.tryFromString(unassignment.sourceSynchronizer),
                  target = DomainId.tryFromString(unassignment.targetSynchronizer),
                  unassignId = unassignment.unassignId,
                  counter = unassignment.reassignmentCounter,
                  contractId = new javaApi.codegen.ContractId(unassignment.contractId),
                ),
              )
            ),
            domainId = DomainId.tryFromString(unassignment.sourceSynchronizer),
          ),
          unassignment.migrationId,
        )
    }

  def httpToJavaEvent(http: httpApi.TreeEvent): javaApi.TreeEvent = http match {
    case httpApi.TreeEvent.members.CreatedEvent(createdHttp) => httpToJavaCreatedEvent(createdHttp)
    case httpApi.TreeEvent.members.ExercisedEvent(exercisedHttp) =>
      httpToJavaExercisedEvent(exercisedHttp)
  }

  def httpToJavaCreatedEvent(http: httpApi.CreatedEvent): javaApi.CreatedEvent = {
    val templateId = parseTemplateId(http.templateId)
    new javaApi.CreatedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(),
      http.eventId,
      templateId,
      http.packageName,
      http.contractId,
      decodeContractPayload(templateId, http.createArguments),
      /*createdEventBlob = */ ByteString.EMPTY,
      /*interfaceViews = */ java.util.Collections.emptyMap(),
      /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
      /* contractKey = */ None.toJava,
      http.signatories.asJava,
      http.observers.asJava,
      http.createdAt.toInstant,
    )
  }

  def httpToJavaExercisedEvent(http: httpApi.ExercisedEvent): javaApi.ExercisedEvent = {
    val templateId = parseTemplateId(http.templateId)
    new javaApi.ExercisedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(),
      http.eventId,
      templateId,
      http.packageName,
      http.interfaceId.map(parseTemplateId(_)).toJava,
      http.contractId,
      http.choice,
      decodeChoiceArgument(templateId, http.choice, http.choiceArgument),
      http.actingParties.asJava,
      http.consuming,
      http.childEventIds.asJava,
      decodeExerciseResult(templateId, http.choice, http.exerciseResult),
    )
  }

  private def templateIdString(templateId: javaApi.Identifier) =
    s"${templateId.getPackageId}:${templateId.getModuleName}:${templateId.getEntityName}"

  private def parseTemplateId(templateId: String) = {
    val pattern = "(.*):(.*):(.*)".r
    val split = pattern
      .findFirstMatchIn(templateId)
      .getOrElse(
        throw new IllegalStateException(s"Cannot parse template Id $templateId")
      )
    val (packageId, moduleName, entityName) = (split.group(1), split.group(2), split.group(3))
    new javaApi.Identifier(packageId, moduleName, entityName)
  }

  private def failedToWriteToJson(err: String): Nothing =
    throw new IllegalStateException(s"Failed to write to JSON: $err")

  /** Parses a string that is known to contain a valid JSON value to io.circe.Json */
  protected def tryParseJson(validJsonString: String): io.circe.Json =
    io.circe.parser
      .parse(validJsonString)
      .fold(err => failedToWriteToJson(err.message), identity)

  def encodeContractPayload(event: javaApi.CreatedEvent)(implicit
      elc: ErrorLoggingContext
  ): io.circe.Json

  def decodeContractPayload(templateId: javaApi.Identifier, json: io.circe.Json): javaApi.DamlRecord

  def encodeChoiceArgument(event: javaApi.ExercisedEvent)(implicit
      elc: ErrorLoggingContext
  ): io.circe.Json

  def decodeChoiceArgument(
      templateId: javaApi.Identifier,
      choice: String,
      json: io.circe.Json,
  ): javaApi.Value

  def encodeExerciseResult(event: javaApi.ExercisedEvent)(implicit
      elc: ErrorLoggingContext
  ): io.circe.Json

  def decodeExerciseResult(
      templateId: javaApi.Identifier,
      choice: String,
      json: io.circe.Json,
  ): javaApi.Value

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
  override def encodeContractPayload(
      event: javaApi.CreatedEvent
  )(implicit elc: ErrorLoggingContext): Json =
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
      event: javaApi.ExercisedEvent
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
      event: javaApi.ExercisedEvent
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

  override def decodeContractPayload(
      templateId: javaApi.Identifier,
      json: Json,
  ): javaApi.DamlRecord =
    ValueJsonCodecCodegen
      .deserializableContractPayload(templateId, json.noSpaces)
      .fold(
        error => throw new RuntimeException(s"Failed to decode contract payload: $error"),
        withoutFieldLabels,
      )

  override def decodeChoiceArgument(
      templateId: javaApi.Identifier,
      choice: String,
      json: Json,
  ): javaApi.Value =
    ValueJsonCodecCodegen
      .deserializeChoiceArgument(templateId, choice, json.noSpaces)
      .fold(
        error => throw new RuntimeException(s"Failed to decode choice argument: $error"),
        withoutFieldLabels,
      )

  override def decodeExerciseResult(
      templateId: javaApi.Identifier,
      choice: String,
      json: Json,
  ): javaApi.Value =
    ValueJsonCodecCodegen
      .deserializeChoiceResult(templateId, choice, json.noSpaces)
      .fold(
        error => throw new RuntimeException(s"Failed to decode choice result: $error"),
        withoutFieldLabels,
      )

  /** Recursively removes all field labels from a value.
    * ValueJsonCodecCodegen returns values with field labels, but we generally don't store field labels in databases.
    * The labels are removed to make values comparable.
    */
  private def withoutFieldLabels(value: javaApi.Value): javaApi.Value = {
    value match {
      case record: javaApi.DamlRecord => withoutFieldLabels(record)
      case list: javaApi.DamlList => javaApi.DamlList.of(list.toList(withoutFieldLabels))
      case tmap: javaApi.DamlTextMap => javaApi.DamlTextMap.of(tmap.toMap(withoutFieldLabels))
      case gmap: javaApi.DamlGenMap =>
        javaApi.DamlGenMap.of(gmap.toMap(withoutFieldLabels, withoutFieldLabels))
      case opt: javaApi.DamlOptional =>
        javaApi.DamlOptional.of(opt.getValue.map(withoutFieldLabels))
      case variant: javaApi.Variant =>
        new javaApi.Variant(variant.getConstructor, withoutFieldLabels(variant.getValue))
      case _ => value
    }
  }
  private def withoutFieldLabels(value: javaApi.DamlRecord): javaApi.DamlRecord = {
    val fields = value.getFields.asScala.toList
    val fieldsWithoutLabels = fields.map { f =>
      new javaApi.DamlRecord.Field(withoutFieldLabels(f.getValue))
    }
    new javaApi.DamlRecord(fieldsWithoutLabels.asJava)
  }
}

// A lossless, but harder to process, encoding. Should be used only for backfilling Scan.
case object LosslessScanHttpEncodings extends ScanHttpEncodings {
  import com.daml.network.util.ValueJsonCodecProtobuf
  override def encodeContractPayload(
      event: javaApi.CreatedEvent
  )(implicit elc: ErrorLoggingContext): Json =
    tryParseJson(
      ValueJsonCodecProtobuf
        .serializeValue(event.getArguments)
    )

  override def encodeChoiceArgument(
      event: javaApi.ExercisedEvent
  )(implicit elc: ErrorLoggingContext): Json =
    tryParseJson(
      ValueJsonCodecProtobuf
        .serializeValue(event.getChoiceArgument)
    )

  override def encodeExerciseResult(
      event: javaApi.ExercisedEvent
  )(implicit elc: ErrorLoggingContext): Json =
    tryParseJson(
      ValueJsonCodecProtobuf
        .serializeValue(event.getExerciseResult)
    )

  override def decodeContractPayload(
      templateId: javaApi.Identifier,
      json: Json,
  ): javaApi.DamlRecord =
    ValueJsonCodecProtobuf.deserializeValue(json.toString()).asRecord().get()

  override def decodeChoiceArgument(
      templateId: javaApi.Identifier,
      choice: String,
      json: Json,
  ): javaApi.Value =
    ValueJsonCodecProtobuf.deserializeValue(json.toString())

  override def decodeExerciseResult(
      templateId: javaApi.Identifier,
      choice: String,
      json: Json,
  ): javaApi.Value =
    ValueJsonCodecProtobuf.deserializeValue(json.toString())
}
