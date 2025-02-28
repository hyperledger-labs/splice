// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.{data, data as javaApi}
import com.daml.ledger.javaapi.data.TransactionTree
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.google.protobuf.ByteString
import io.circe.Json
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.ledger.api as ledgerApi
import org.lfdecentralizedtrust.splice.http.v0.definitions.TreeEvent.members
import org.lfdecentralizedtrust.splice.http.v0.{definitions, definitions as httpApi}
import org.lfdecentralizedtrust.splice.http.v0.definitions.ValidatorReceivedFaucets
import org.lfdecentralizedtrust.splice.store.TreeUpdateWithMigrationId
import org.lfdecentralizedtrust.splice.util.{Contract, EventId, LegacyOffset, Trees}

import java.time.{Instant, ZoneOffset}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Transcodes between different representations of ledger updates:
  *
  * lapi: org.lfdecentralizedtrust.splice.environment.ledger.api.*
  * java: com.daml.ledger.javaapi.data.*
  * http: org.lfdecentralizedtrust.splice.http.v0.httpApi.*
  */
sealed trait ScanHttpEncodings {

  def lapiToHttpUpdate(
      updateWithMigrationId: TreeUpdateWithMigrationId,
      eventIdBuilder: (String, Int) => String,
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
              updateWithMigrationId.update.synchronizerId.toProtoPrimitive,
              tree.getEffectiveAt.toString,
              LegacyOffset.Api.fromLong(tree.getOffset),
              tree.getRootNodeIds.asScala
                .map(eventIdBuilder(tree.getUpdateId, _))
                .toVector,
              tree.getEventsById.asScala.map { case (nodeId, treeEvent) =>
                val eventId = eventIdBuilder(tree.getUpdateId, nodeId)
                eventId -> javaToHttpEvent(
                  tree,
                  eventId,
                  treeEvent,
                  eventIdBuilder,
                )
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
                LegacyOffset.Api.fromLong(update.offset),
                update.recordTime.toString,
                httpApi.UpdateHistoryAssignment(
                  submitter.toProtoPrimitive,
                  source.toProtoPrimitive,
                  target.toProtoPrimitive,
                  updateWithMigrationId.migrationId,
                  unassignId,
                  javaToHttpCreatedEvent(
                    eventIdBuilder(update.updateId, createdEvent.getNodeId),
                    createdEvent,
                  ),
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
                LegacyOffset.Api.fromLong(update.offset),
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

  private def javaToHttpEvent(
      tree: TransactionTree,
      eventId: String,
      treeEvent: javaApi.TreeEvent,
      eventIdBuild: (String, Int) => String,
  )(implicit
      elc: ErrorLoggingContext
  ): httpApi.TreeEvent = {
    treeEvent match {
      case event: javaApi.CreatedEvent =>
        httpApi.TreeEvent.fromCreatedEvent(
          javaToHttpCreatedEvent(eventId, event)
        )
      case event: javaApi.ExercisedEvent =>
        httpApi.TreeEvent.fromExercisedEvent(
          httpApi
            .ExercisedEvent(
              "exercised_event",
              eventId,
              event.getContractId,
              templateIdString(event.getTemplateId),
              event.getPackageName,
              event.getChoice,
              encodeChoiceArgument(event),
              tree
                .getChildNodeIds(event)
                .asScala
                .map(
                  eventIdBuild(tree.getUpdateId, _)
                )
                .toVector,
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

  def javaToHttpCreatedEvent(eventId: String, event: javaApi.CreatedEvent)(implicit
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
        eventId,
        event.getContractId,
        templateIdString(event.getTemplateId),
        event.getPackageName,
        encodeContractPayload(event),
        event.getCreatedAt.atOffset(ZoneOffset.UTC),
        event.getSignatories.asScala.toVector.sorted,
        event.getObservers.asScala.toVector.sorted,
      )
  }

  def httpToLapiUpdate(http: httpApi.UpdateHistoryItem): TreeUpdateWithMigrationId = http match {
    case httpApi.UpdateHistoryItem.members.UpdateHistoryTransaction(httpTransaction) =>
      httpToLapiTransaction(httpTransaction)
    case httpApi.UpdateHistoryItem.members.UpdateHistoryReassignment(httpReassignment) =>
      httpToLapiReassignment(httpReassignment)
  }

  private def httpToLapiTransaction(
      http: httpApi.UpdateHistoryTransaction
  ): TreeUpdateWithMigrationId = {
    val nodesWithChildren = http.eventsById.map {
      case (eventId, _: members.CreatedEvent) =>
        EventId.nodeIdFromEventId(eventId) -> Seq.empty
      case (eventId, exercise: members.ExercisedEvent) =>
        EventId.nodeIdFromEventId(eventId) -> exercise.value.childEventIds.map(
          EventId.nodeIdFromEventId
        )
    }
    TreeUpdateWithMigrationId(
      ledgerApi.LedgerClient.GetTreeUpdatesResponse(
        update = ledgerApi.TransactionTreeUpdate(
          new javaApi.TransactionTree(
            http.updateId,
            "",
            http.workflowId,
            Instant.parse(http.effectiveAt),
            LegacyOffset.Api.assertFromStringToLong(http.offset),
            http.eventsById.map { case (eventId, treeEventHttp) =>
              Integer.valueOf(EventId.nodeIdFromEventId(eventId)) -> httpToJavaEvent(
                nodesWithChildren,
                treeEventHttp,
              )
            }.asJava,
            http.synchronizerId,
            TraceContextOuterClass.TraceContext.getDefaultInstance,
            Instant.parse(http.recordTime),
          )
        ),
        synchronizerId = SynchronizerId.tryFromString(http.synchronizerId),
      ),
      http.migrationId,
    )
  }

  def httpToLapiReassignment(http: httpApi.UpdateHistoryReassignment): TreeUpdateWithMigrationId =
    http.event match {
      case httpApi.UpdateHistoryReassignment.Event.members.UpdateHistoryAssignment(assignment) =>
        TreeUpdateWithMigrationId(
          ledgerApi.LedgerClient.GetTreeUpdatesResponse(
            update = ledgerApi.ReassignmentUpdate(
              transfer = ledgerApi.Reassignment(
                updateId = http.updateId,
                offset = LegacyOffset.Api.assertFromStringToLong(http.offset),
                recordTime = CantonTimestamp.assertFromInstant(Instant.parse(http.recordTime)),
                event = ledgerApi.ReassignmentEvent.Assign(
                  submitter = PartyId.tryFromProtoPrimitive(assignment.submitter),
                  source = SynchronizerId.tryFromString(assignment.sourceSynchronizer),
                  target = SynchronizerId.tryFromString(assignment.targetSynchronizer),
                  unassignId = assignment.unassignId,
                  createdEvent = httpToJavaCreatedEvent(assignment.createdEvent),
                  counter = assignment.reassignmentCounter,
                ),
              )
            ),
            synchronizerId = SynchronizerId.tryFromString(assignment.targetSynchronizer),
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
                offset = LegacyOffset.Api.assertFromStringToLong(http.offset),
                recordTime = CantonTimestamp.assertFromInstant(Instant.parse(http.recordTime)),
                event = ledgerApi.ReassignmentEvent.Unassign(
                  submitter = PartyId.tryFromProtoPrimitive(unassignment.submitter),
                  source = SynchronizerId.tryFromString(unassignment.sourceSynchronizer),
                  target = SynchronizerId.tryFromString(unassignment.targetSynchronizer),
                  unassignId = unassignment.unassignId,
                  counter = unassignment.reassignmentCounter,
                  contractId = new javaApi.codegen.ContractId(unassignment.contractId),
                ),
              )
            ),
            synchronizerId = SynchronizerId.tryFromString(unassignment.sourceSynchronizer),
          ),
          unassignment.migrationId,
        )
    }

  private def httpToJavaEvent(
      nodesWithChildren: Map[Int, Seq[Int]],
      http: httpApi.TreeEvent,
  ): javaApi.TreeEvent = http match {
    case httpApi.TreeEvent.members.CreatedEvent(createdHttp) => httpToJavaCreatedEvent(createdHttp)
    case httpApi.TreeEvent.members.ExercisedEvent(exercisedHttp) =>
      httpToJavaExercisedEvent(nodesWithChildren, exercisedHttp)
  }

  private def httpToJavaCreatedEvent(http: httpApi.CreatedEvent): javaApi.CreatedEvent = {
    val templateId = parseTemplateId(http.templateId)
    new javaApi.CreatedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(),
      /*offset = */ 0, // not populated
      /*nodeId = */ EventId.nodeIdFromEventId(http.eventId),
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

  private def httpToJavaExercisedEvent(
      nodesWithChildren: Map[Int, Seq[Int]],
      http: httpApi.ExercisedEvent,
  ): javaApi.ExercisedEvent = {
    val templateId = parseTemplateId(http.templateId)
    val interfaceId = http.interfaceId.map(parseTemplateId)
    val nodeId = EventId.nodeIdFromEventId(http.eventId)
    new javaApi.ExercisedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(),
      /*offset = */ 0, // not populated
      /*nodeId = */ nodeId,
      templateId,
      http.packageName,
      interfaceId.toJava,
      http.contractId,
      http.choice,
      decodeChoiceArgument(templateId, interfaceId, http.choice, http.choiceArgument),
      http.actingParties.asJava,
      http.consuming,
      EventId.lastDescendedNodeFromChildNodeIds(
        nodeId,
        nodesWithChildren,
      ),
      decodeExerciseResult(templateId, interfaceId, http.choice, http.exerciseResult),
    )
  }

  def templateIdString(templateId: javaApi.Identifier) =
    s"${templateId.getPackageId}:${templateId.getModuleName}:${templateId.getEntityName}"

  def parseTemplateId(templateId: String) = {
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
      interfaceId: Option[javaApi.Identifier],
      choice: String,
      json: io.circe.Json,
  ): javaApi.Value

  def encodeExerciseResult(event: javaApi.ExercisedEvent)(implicit
      elc: ErrorLoggingContext
  ): io.circe.Json

  def decodeExerciseResult(
      templateId: javaApi.Identifier,
      interfaceId: Option[javaApi.Identifier],
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

object ScanHttpEncodings {

  sealed trait ApiVersion
  case object V0 extends ApiVersion
  case object V1 extends ApiVersion

  def encodeUpdate(
      update: TreeUpdateWithMigrationId,
      encoding: definitions.DamlValueEncoding,
      version: ApiVersion,
  )(implicit
      elc: ErrorLoggingContext
  ): definitions.UpdateHistoryItem = {
    val update2 = version match {
      case V0 =>
        update
      case V1 =>
        ScanHttpEncodings.makeConsistentAcrossSvs(update)
    }
    val encodings: ScanHttpEncodings = encoding match {
      case definitions.DamlValueEncoding.members.CompactJson => CompactJsonScanHttpEncodings
      case definitions.DamlValueEncoding.members.ProtobufJson => ProtobufJsonScanHttpEncodings
    }
    // v0 always returns the update ids as `#` prefixed,as that's the way they were encoded in canton. v1 returns it without the `#`
    encodings.lapiToHttpUpdate(
      update2,
      version match {
        case V0 =>
          EventId.prefixedFromUpdateIdAndNodeId
        case V1 =>
          EventId.noPrefixFromUpdateIdAndNodeId
      },
    )
  }

  /** Returns a copy of the input, modified such that the result is consistent across different SVs:
    * - Offsets are replaced by empty strings
    * - Event ids are replaced by deterministically assigned event ids
    *
    * Note: both offsets and event ids are assigned locally by the participant.
    */
  def makeConsistentAcrossSvs(
      update: TreeUpdateWithMigrationId
  ): TreeUpdateWithMigrationId = {
    update.copy(update = makeConsistentAcrossSvs(update.update))
  }

  def makeConsistentAcrossSvs(
      response: ledgerApi.LedgerClient.GetTreeUpdatesResponse
  ): ledgerApi.LedgerClient.GetTreeUpdatesResponse = {
    response.copy(update = makeConsistentAcrossSvs(response.update))
  }

  def makeConsistentAcrossSvs(
      update: ledgerApi.TreeUpdate
  ): ledgerApi.TreeUpdate = {
    update match {
      case ledgerApi.TransactionTreeUpdate(tree) =>
        ledgerApi.TransactionTreeUpdate(
          makeConsistentAcrossSvs(tree)
        )
      case ledgerApi.ReassignmentUpdate(transfer) =>
        transfer.event match {
          case assign: ledgerApi.ReassignmentEvent.Assign =>
            ledgerApi.ReassignmentUpdate(
              ledgerApi.Reassignment(
                transfer.updateId,
                1L,
                transfer.recordTime,
                assign.copy(
                  createdEvent = new javaApi.CreatedEvent(
                    assign.createdEvent.getWitnessParties,
                    assign.createdEvent.getOffset,
                    assign.createdEvent.getNodeId,
                    assign.createdEvent.getTemplateId,
                    assign.createdEvent.getPackageName,
                    assign.createdEvent.getContractId,
                    assign.createdEvent.getArguments,
                    assign.createdEvent.getCreatedEventBlob,
                    assign.createdEvent.getInterfaceViews,
                    assign.createdEvent.getFailedInterfaceViews,
                    assign.createdEvent.getContractKey,
                    assign.createdEvent.getSignatories,
                    assign.createdEvent.getObservers,
                    assign.createdEvent.createdAt,
                  )
                ),
              )
            )
          case unassign: ledgerApi.ReassignmentEvent.Unassign =>
            ledgerApi.ReassignmentUpdate(
              ledgerApi.Reassignment(
                transfer.updateId,
                1L,
                transfer.recordTime,
                unassign,
              )
            )
        }
    }
  }

  def makeConsistentAcrossSvs(
      tree: javaApi.TransactionTree
  ): javaApi.TransactionTree = {
    val mapping = Trees
      .getLocalEventIndices(tree)
    val nodesWithChildren = tree.getEventsById.asScala.map {
      case (nodeId, exercised: data.ExercisedEvent) =>
        mapping(nodeId.intValue()) -> tree
          .getChildNodeIds(exercised)
          .asScala
          .toSeq
          .map(_.intValue())
          .map(mapping)
      case (nodeId, _) => mapping(nodeId.intValue()) -> Seq.empty
    }
    val eventsById = tree.getEventsById.asScala.map {
      case (nodeId, created: javaApi.CreatedEvent) =>
        mapping(nodeId) -> new javaApi.CreatedEvent(
          created.getWitnessParties,
          created.getOffset,
          mapping(created.getNodeId),
          created.getTemplateId,
          created.getPackageName,
          created.getContractId,
          created.getArguments,
          created.getCreatedEventBlob,
          created.getInterfaceViews,
          created.getFailedInterfaceViews,
          created.getContractKey,
          created.getSignatories,
          created.getObservers,
          created.createdAt,
        )
      case (nodeId, exercised: javaApi.ExercisedEvent) =>
        val newNodeId = mapping(exercised.getNodeId)
        mapping(nodeId) -> new javaApi.ExercisedEvent(
          exercised.getWitnessParties,
          exercised.getOffset,
          newNodeId,
          exercised.getTemplateId,
          exercised.getPackageName,
          exercised.getInterfaceId,
          exercised.getContractId,
          exercised.getChoice,
          exercised.getChoiceArgument,
          exercised.getActingParties,
          exercised.isConsuming,
          EventId.lastDescendedNodeFromChildNodeIds(
            newNodeId,
            nodesWithChildren.toMap,
          ),
          exercised.getExerciseResult,
        )
      case (_, event) => sys.error(s"Unexpected event type: $event")
    }

    new javaApi.TransactionTree(
      tree.getUpdateId,
      tree.getCommandId,
      tree.getWorkflowId,
      tree.getEffectiveAt,
      1L, // tree.getOffset not used as the values are participant local and we want consistency across svs
      eventsById.map { case (key, value) =>
        Integer.valueOf(key) -> value
      }.asJava,
      tree.getSynchronizerId,
      tree.getTraceContext,
      tree.getRecordTime,
    )
  }
}

// A lossy, but much easier to process, encoding. Should be used for all endpoints not used for backfilling Scan.
case object CompactJsonScanHttpEncodings extends ScanHttpEncodings {
  import org.lfdecentralizedtrust.splice.util.ValueJsonCodecCodegen
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
        error =>
          throw new RuntimeException(
            s"Failed to decode contract payload '${json.noSpaces}': $error"
          ),
        withoutFieldLabels,
      )

  override def decodeChoiceArgument(
      templateId: javaApi.Identifier,
      interfaceId: Option[javaApi.Identifier],
      choice: String,
      json: Json,
  ): javaApi.Value =
    ValueJsonCodecCodegen
      .deserializeChoiceArgument(templateId, interfaceId, choice, json.noSpaces)
      .fold(
        error =>
          throw new RuntimeException(
            s"Failed to decode choice argument '${json.noSpaces}': $error"
          ),
        withoutFieldLabels,
      )

  override def decodeExerciseResult(
      templateId: javaApi.Identifier,
      interfaceId: Option[javaApi.Identifier],
      choice: String,
      json: Json,
  ): javaApi.Value =
    ValueJsonCodecCodegen
      .deserializeChoiceResult(templateId, interfaceId, choice, json.noSpaces)
      .fold(
        error =>
          throw new RuntimeException(s"Failed to decode choice result '${json.noSpaces}': $error"),
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
case object ProtobufJsonScanHttpEncodings extends ScanHttpEncodings {
  import org.lfdecentralizedtrust.splice.util.ValueJsonCodecProtobuf
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
      interfaceId: Option[javaApi.Identifier],
      choice: String,
      json: Json,
  ): javaApi.Value =
    ValueJsonCodecProtobuf.deserializeValue(json.toString())

  override def decodeExerciseResult(
      templateId: javaApi.Identifier,
      interfaceId: Option[javaApi.Identifier],
      choice: String,
      json: Json,
  ): javaApi.Value =
    ValueJsonCodecProtobuf.deserializeValue(json.toString())
}

object FaucetProcessor {
  def process(
      licenses: Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]
  ): Vector[ValidatorReceivedFaucets] = {
    licenses.map { license =>
      val numRoundsCollected = license.payload.faucetState
        .map { faucetState =>
          faucetState.lastReceivedFor.number - faucetState.firstReceivedFor.number - faucetState.numCouponsMissed + 1
        }
        .orElse(0L)

      ValidatorReceivedFaucets(
        validator = license.payload.validator,
        numRoundsCollected = numRoundsCollected,
        numRoundsMissed =
          license.payload.faucetState.map(_.numCouponsMissed.longValue()).orElse(0L),
        firstCollectedInRound =
          license.payload.faucetState.map(_.firstReceivedFor.number.longValue()).orElse(0L),
        lastCollectedInRound =
          license.payload.faucetState.map(_.lastReceivedFor.number.longValue()).orElse(0L),
      )
    }.toVector
  }
}
