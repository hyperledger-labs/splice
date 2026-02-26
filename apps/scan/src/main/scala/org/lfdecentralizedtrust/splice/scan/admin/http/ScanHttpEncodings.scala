// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.{data, data as javaApi}
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.google.protobuf.ByteString
import io.circe.Json
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.ledger.api as ledgerApi
import org.lfdecentralizedtrust.splice.http.v0.definitions.TreeEvent.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.ValidatorReceivedFaucets
import org.lfdecentralizedtrust.splice.http.v0.{definitions, definitions as httpApi}
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.AppActivityRecordT
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore.{
  TrafficSummaryT,
  TransactionViewT,
  VerdictResultDbValue,
  VerdictT,
}
import org.lfdecentralizedtrust.splice.store.TreeUpdateWithMigrationId
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.{Codec, Contract, EventId, LegacyOffset, Trees}

import java.time.format.DateTimeFormatterBuilder
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
              ScanHttpEncodings.formatRecordTime(tree.getRecordTime),
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
                ScanHttpEncodings.formatRecordTime(update.recordTime.toInstant),
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
                ScanHttpEncodings.formatRecordTime(update.recordTime.toInstant),
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
      tree: javaApi.Transaction,
      eventId: String,
      treeEvent: javaApi.Event,
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

  def httpToLapiUpdate(http: httpApi.UpdateHistoryItemV2): TreeUpdateWithMigrationId = http match {
    case httpApi.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(httpTransaction) =>
      httpToLapiTransaction(httpTransaction, 1L) // offset not used in v2
    case httpApi.UpdateHistoryItemV2.members.UpdateHistoryReassignment(httpReassignment) =>
      httpToLapiReassignment(httpReassignment)
  }

  def httpToLapiUpdate(http: httpApi.UpdateHistoryItem): TreeUpdateWithMigrationId = http match {
    case httpApi.UpdateHistoryItem.members.UpdateHistoryTransaction(httpTransaction) =>
      httpToLapiTransaction(httpTransaction)
    case httpApi.UpdateHistoryItem.members.UpdateHistoryReassignment(httpReassignment) =>
      httpToLapiReassignment(httpReassignment)
  }
  def httpToLapiTransaction(
      httpV2: httpApi.UpdateHistoryTransactionV2,
      offset: Long,
  ): TreeUpdateWithMigrationId = {
    val http = httpApi.UpdateHistoryTransaction(
      updateId = httpV2.updateId,
      migrationId = httpV2.migrationId,
      workflowId = httpV2.workflowId,
      recordTime = httpV2.recordTime,
      synchronizerId = httpV2.synchronizerId,
      effectiveAt = httpV2.effectiveAt,
      offset = LegacyOffset.Api.fromLong(offset),
      rootEventIds = httpV2.rootEventIds,
      eventsById = httpV2.eventsById,
    )
    httpToLapiTransaction(http)
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
      UpdateHistoryResponse(
        update = ledgerApi.TransactionTreeUpdate(
          new javaApi.Transaction(
            http.updateId,
            "",
            http.workflowId,
            Instant.parse(http.effectiveAt),
            http.eventsById
              .map { case (eventId, treeEventHttp) =>
                Integer.valueOf(EventId.nodeIdFromEventId(eventId)) -> httpToJavaEvent(
                  nodesWithChildren,
                  treeEventHttp,
                )
              }
              .toSeq
              .sortBy(_._1)
              .map(_._2)
              .asJava,
            LegacyOffset.Api.assertFromStringToLong(http.offset),
            http.synchronizerId,
            TraceContextOuterClass.TraceContext.getDefaultInstance,
            Instant.parse(http.recordTime),
            ByteString.EMPTY, // TODO(#3408): Revisit when adding APIs
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
          UpdateHistoryResponse(
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
          UpdateHistoryResponse(
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
  ): javaApi.Event = http match {
    case httpApi.TreeEvent.members.CreatedEvent(createdHttp) => httpToJavaCreatedEvent(createdHttp)
    case httpApi.TreeEvent.members.ExercisedEvent(exercisedHttp) =>
      httpToJavaExercisedEvent(nodesWithChildren, exercisedHttp)
  }

  def httpToJavaCreatedEvent(http: httpApi.CreatedEvent): javaApi.CreatedEvent = {
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
      /* acsDelta = */ false,
      /* representativePackageId = */ templateId.getPackageId,
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
      /*implementedInterfaces = */ java.util.Collections.emptyList(),
      /*acsDelta = */ false,
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

  private val recordTimeDateFormatter =
    new DateTimeFormatterBuilder().appendInstant(6).toFormatter()
  def formatRecordTime(instant: Instant): String =
    recordTimeDateFormatter.format(instant)

  def encodeVerdict(
      verdict: VerdictT,
      views: Seq[TransactionViewT],
  ): definitions.EventHistoryVerdict = {
    val verdictResultEnum: definitions.VerdictResult = verdict.verdictResult match {
      case VerdictResultDbValue.Accepted =>
        definitions.VerdictResult.VerdictResultAccepted
      case VerdictResultDbValue.Rejected =>
        definitions.VerdictResult.VerdictResultRejected
      case _ => definitions.VerdictResult.VerdictResultUnspecified
    }

    val txViewsList: Vector[definitions.TransactionView] =
      views.sortBy(_.viewId).toVector.map { v =>
        val quorums: Vector[definitions.Quorum] = v.confirmingParties.asArray
          .getOrElse(Vector.empty)
          .flatMap { j =>
            val parties = j.hcursor.downField("parties").as[Vector[String]].getOrElse(Vector.empty)
            val threshold = j.hcursor.downField("threshold").as[Int].getOrElse(0)
            Some(definitions.Quorum(parties, threshold))
          }
        definitions.TransactionView(
          viewId = v.viewId,
          informees = v.informees.toVector,
          confirmingParties = quorums,
          subViews = v.subViews.toVector,
          // TODO(#4091): view hashes were never populated in production, add them back here if required
          viewHash = "",
        )
      }

    val txViews = definitions.TransactionViews(
      views = txViewsList,
      rootViews = verdict.transactionRootViews.toVector,
    )

    httpApi.EventHistoryVerdict(
      updateId = verdict.updateId,
      migrationId = verdict.migrationId,
      domainId = Codec.encode(verdict.domainId),
      recordTime = formatRecordTime(verdict.recordTime.toInstant),
      finalizationTime = formatRecordTime(verdict.finalizationTime.toInstant),
      submittingParties = verdict.submittingParties.toVector,
      submittingParticipantUid = verdict.submittingParticipantUid,
      verdictResult = verdictResultEnum,
      mediatorGroup = verdict.mediatorGroup,
      transactionViews = txViews,
    )
  }

  def encodeTrafficSummary(
      summary: TrafficSummaryT
  ): definitions.EventHistoryTrafficSummary = {
    val envelopes = summary.envelopeTrafficSummarys.map { env =>
      definitions.EnvelopeTrafficCost(
        trafficCost = env.trafficCost,
        viewIds = env.viewIds.toVector,
      )
    }.toVector
    definitions.EventHistoryTrafficSummary(
      totalTrafficCost = summary.totalTrafficCost,
      envelopeTrafficCosts = envelopes,
    )
  }

  def encodeAppActivityRecord(
      record: AppActivityRecordT
  ): definitions.EventHistoryAppActivityRecords = {
    val records = record.appProviderParties
      .zip(record.appActivityWeights)
      .map { case (party, weight) =>
        definitions.AppActivityRecord(party = party, weight = weight)
      }
      .toVector
    definitions.EventHistoryAppActivityRecords(
      roundNumber = record.roundNumber,
      records = records,
    )
  }

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
      case definitions.DamlValueEncoding.members.CompactJson => CompactJsonScanHttpEncodings()
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
      response: UpdateHistoryResponse
  ): UpdateHistoryResponse = {
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
                    assign.createdEvent.isAcsDelta,
                    assign.createdEvent.getRepresentativePackageId,
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
      tree: javaApi.Transaction
  ): javaApi.Transaction = {
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
    val eventsById: Iterable[(Int, javaApi.Event)] = tree.getEventsById.asScala.map {
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
          created.isAcsDelta,
          created.getRepresentativePackageId,
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
          exercised.getImplementedInterfaces,
          exercised.isAcsDelta,
        )
      case (_, event) => sys.error(s"Unexpected event type: $event")
    }

    new javaApi.Transaction(
      tree.getUpdateId,
      tree.getCommandId,
      tree.getWorkflowId,
      tree.getEffectiveAt,
      eventsById.toList.sortBy(_._1).map(_._2).asJava,
      1L, // tree.getOffset not used as the values are participant local and we want consistency across svs
      tree.getSynchronizerId,
      tree.getTraceContext,
      tree.getRecordTime,
      ByteString.EMPTY, // TODO(#3408): Revisit when adding APIs
    )
  }
}

object RemoveFieldLabels {
  def record(value: javaApi.DamlRecord): javaApi.DamlRecord = recordWithoutFieldLabels(value)
  def value(value: javaApi.Value): javaApi.Value = valueWithoutFieldLabels(value)

  /** Recursively removes all field labels from a value.
    * ValueJsonCodecCodegen returns values with field labels, but we generally don't store field labels in databases.
    * The labels are removed to make values comparable.
    */
  private def valueWithoutFieldLabels(value: javaApi.Value): javaApi.Value = {
    value match {
      case record: javaApi.DamlRecord => recordWithoutFieldLabels(record)
      case list: javaApi.DamlList => javaApi.DamlList.of(list.toList(valueWithoutFieldLabels))
      case tmap: javaApi.DamlTextMap => javaApi.DamlTextMap.of(tmap.toMap(valueWithoutFieldLabels))
      case gmap: javaApi.DamlGenMap =>
        javaApi.DamlGenMap.of(gmap.toMap(valueWithoutFieldLabels, valueWithoutFieldLabels))
      case opt: javaApi.DamlOptional =>
        javaApi.DamlOptional.of(opt.getValue.map(valueWithoutFieldLabels))
      case variant: javaApi.Variant =>
        new javaApi.Variant(variant.getConstructor, valueWithoutFieldLabels(variant.getValue))
      case _ => value
    }
  }
  private def recordWithoutFieldLabels(value: javaApi.DamlRecord): javaApi.DamlRecord = {
    val fields = value.getFields.asScala.toList
    val fieldsWithoutLabels = fields.map { f =>
      new javaApi.DamlRecord.Field(valueWithoutFieldLabels(f.getValue))
    }
    new javaApi.DamlRecord(fieldsWithoutLabels.asJava)
  }
}

case class CompactJsonScanHttpEncodings(
    transformValue: javaApi.Value => javaApi.Value,
    transformRecord: javaApi.DamlRecord => javaApi.DamlRecord,
) extends ScanHttpEncodings {
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
        transformRecord,
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
        transformValue,
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
        transformValue,
      )
}

// A lossy, but much easier to process, encoding. Should be used for all endpoints not used for backfilling Scan.
object CompactJsonScanHttpEncodings {
  def apply() = new CompactJsonScanHttpEncodings(RemoveFieldLabels.value, RemoveFieldLabels.record)
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
