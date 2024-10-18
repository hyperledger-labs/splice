package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  ScanAppBackendReference,
  ScanAppClientReference,
  SvAppBackendReference,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  LedgerClient,
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
  TreeUpdate,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.{
  CompactJson,
  ProtobufJson,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.scan.admin.http.{
  CompactJsonScanHttpEncodings,
  ProtobufJsonScanHttpEncodings,
  ScanHttpEncodings,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistoryTestBase.{
  LostInScanApi,
  LostInStoreIngestion,
}
import org.lfdecentralizedtrust.splice.store.{PageLimit, UpdateHistory, UpdateHistoryTestBase}
import com.daml.ledger.javaapi.data.*
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.{
  AssignedWrapper,
  TransactionTreeWrapper,
  UnassignedWrapper,
}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import org.scalatest.Assertion
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait UpdateHistoryTestUtil extends TestCommon {

  // The ledger API drops trailing empty optionals in non-verbose mode since https://github.com/digital-asset/daml/pull/19989
  // so for equality comparisons we also drop it from our types
  private def dropTrailingEmptyOptionals(
      updateResponse: GetTreeUpdatesResponse
  ): GetTreeUpdatesResponse = {
    updateResponse.copy(
      update = dropTrailingEmptyOptionals(updateResponse.update)
    )
  }

  private def dropTrailingEmptyOptionals(update: TreeUpdate): TreeUpdate = {
    update match {
      case TransactionTreeUpdate(tree) => TransactionTreeUpdate(dropTrailingEmptyOptionals(tree))
      case ReassignmentUpdate(reassignment) =>
        ReassignmentUpdate(dropTrailingEmptyOptionals(reassignment))
    }
  }

  private def dropTrailingEmptyOptionals(tree: TransactionTree): TransactionTree = {
    new TransactionTree(
      tree.getUpdateId(),
      tree.getCommandId(),
      tree.getWorkflowId(),
      tree.getEffectiveAt,
      tree.getOffset(),
      tree.getEventsById.asScala.view.mapValues(dropTrailingEmptyOptionals(_)).toMap.asJava,
      tree.getRootEventIds,
      tree.getDomainId(),
      tree.getTraceContext(),
      tree.getRecordTime(),
    )
  }

  private def dropTrailingEmptyOptionals(
      reassignment: Reassignment[ReassignmentEvent]
  ): Reassignment[ReassignmentEvent] =
    reassignment.copy(
      event = dropTrailingEmptyOptionals(reassignment.event)
    )

  private def dropTrailingEmptyOptionals(ev: ReassignmentEvent): ReassignmentEvent =
    ev match {
      case unassign: ReassignmentEvent.Unassign => unassign
      case assign: ReassignmentEvent.Assign =>
        assign.copy(
          createdEvent = dropTrailingEmptyOptionals(assign.createdEvent)
        )
    }

  private def dropTrailingEmptyOptionals(event: TreeEvent): TreeEvent = {
    event match {
      case ex: ExercisedEvent => dropTrailingEmptyOptionals(ex)
      case cr: CreatedEvent => dropTrailingEmptyOptionals(cr)
      case _ => sys.error(s"Unexpected event: $event")
    }
  }

  private def dropTrailingEmptyOptionals(ev: ExercisedEvent): ExercisedEvent =
    new ExercisedEvent(
      ev.getWitnessParties(),
      ev.getEventId(),
      ev.getTemplateId(),
      ev.getPackageName(),
      ev.getInterfaceId(),
      ev.getContractId(),
      ev.getChoice(),
      dropTrailingEmptyOptionals(ev.getChoiceArgument()),
      ev.getActingParties(),
      ev.isConsuming(),
      ev.getChildEventIds(),
      dropTrailingEmptyOptionals(ev.getExerciseResult()),
    )

  private def dropTrailingEmptyOptionals(ev: CreatedEvent): CreatedEvent =
    new CreatedEvent(
      ev.getWitnessParties(),
      ev.getEventId(),
      ev.getTemplateId(),
      ev.getPackageName(),
      ev.getContractId(),
      dropTrailingEmptyOptionals(ev.getArguments()),
      ev.getCreatedEventBlob(),
      ev.getInterfaceViews(),
      ev.getFailedInterfaceViews(),
      ev.getContractKey(),
      ev.getSignatories(),
      ev.getObservers(),
      ev.getCreatedAt(),
    )

  private def dropTrailingEmptyOptionals(record: DamlRecord): DamlRecord = {
    val fields = record
      .getFields()
      .asScala
      .reverse
      .dropWhile {
        _.getValue match {
          case opt: DamlOptional =>
            opt.isEmpty()
          case _ => false
        }
      }
      .reverse
      .map(field =>
        field.getLabel().toScala match {
          case None => new DamlRecord.Field(dropTrailingEmptyOptionals(field.getValue()))
          case Some(fieldName) =>
            new DamlRecord.Field(fieldName, dropTrailingEmptyOptionals(field.getValue()))
        }
      )
      .asJava

    record.getRecordId().toScala match {
      case None => new DamlRecord(fields)
      case Some(recordId) => new DamlRecord(recordId, fields)
    }
  }

  private def dropTrailingEmptyOptionals(value: Value): Value = {
    value match {
      case record: DamlRecord => dropTrailingEmptyOptionals(record)
      case variant: Variant =>
        new Variant(
          variant.getConstructor(),
          dropTrailingEmptyOptionals(variant.getValue()),
        )
      case genMap: DamlGenMap =>
        val map = genMap.toMap(identity, identity).asScala
        DamlGenMap.of(map.view.mapValues(dropTrailingEmptyOptionals(_)).toMap.asJava)
      case list: DamlList =>
        DamlList.of(
          list.toList(identity).asScala.map(dropTrailingEmptyOptionals(_)).asJava
        )
      case _ => value
    }
  }

  def updateHistoryFromParticipant(
      beginExclusive: String,
      partyId: PartyId,
      participant: ParticipantClientReference,
  ): Seq[GetTreeUpdatesResponse] = {
    val ledgerEnd = participant.ledger_api.state.end()

    participant.ledger_api.updates
      .trees(
        partyIds = Set(partyId),
        completeAfter = Int.MaxValue,
        beginOffsetExclusive = beginExclusive,
        endOffsetInclusive = ledgerEnd,
        verbose = false,
      )
      .map {
        case TransactionTreeWrapper(protoTree) =>
          GetTreeUpdatesResponse(
            TransactionTreeUpdate(LedgerClient.lapiTreeToJavaTree(protoTree)),
            DomainId.tryFromString(protoTree.domainId),
          )
        case UnassignedWrapper(protoReassignment, protoUnassignEvent) =>
          GetTreeUpdatesResponse(
            ReassignmentUpdate(Reassignment.fromProto(protoReassignment)),
            DomainId.tryFromString(protoUnassignEvent.source),
          )
        case AssignedWrapper(protoReassignment, protoAssignEvent) =>
          GetTreeUpdatesResponse(
            ReassignmentUpdate(Reassignment.fromProto(protoReassignment)),
            DomainId.tryFromString(protoAssignEvent.target),
          )
      }
  }

  def compareHistory(
      participant: ParticipantClientReference,
      updateHistory: UpdateHistory,
      ledgerBegin: String,
      mustIncludeReassignments: Boolean = false,
  ): Assertion = {
    val actualUpdates =
      updateHistoryFromParticipant(ledgerBegin, updateHistory.updateStreamParty, participant)

    val recordedUpdates = updateHistory
      .getUpdates(
        Some(
          (
            0L,
            // The after0 argument to getUpdates() is exclusive, so we need to subtract a small value
            // to include the first element
            actualUpdates.head.update.recordTime.addMicros(-1L),
          )
        ),
        includeImportUpdates = true,
        PageLimit.tryCreate(actualUpdates.size),
      )
      .futureValue

    if (mustIncludeReassignments) {
      recordedUpdates.filter(_.update match {
        case LedgerClient
              .GetTreeUpdatesResponse(ReassignmentUpdate(Reassignment(_, _, _, _: Assign)), _) =>
          true
        case _ => false
      }) should not be empty
      recordedUpdates.filter(_.update match {
        case LedgerClient
              .GetTreeUpdatesResponse(ReassignmentUpdate(Reassignment(_, _, _, _: Unassign)), _) =>
          true
        case _ => false
      }) should not be empty
    }

    // Note: UpdateHistory does not preserve all information in updates,
    // so remove fields that are not preserved before comparing.
    val actualUpdatesWithoutLostData =
      actualUpdates.map(UpdateHistoryTestBase.withoutLostData(_, mode = LostInStoreIngestion))
    val recordedUpdatesWithoutLostData = recordedUpdates.map(_.update)
    actualUpdatesWithoutLostData should contain theSameElementsInOrderAs recordedUpdatesWithoutLostData
  }

  def compareHistoryViaLosslessScanApi(
      scanBackend: ScanAppBackendReference,
      scanClient: ScanAppClientReference,
  ): Assertion = {
    val historyFromStore = scanBackend.appState.store.updateHistory
      .getUpdates(
        None,
        includeImportUpdates = true,
        PageLimit.tryCreate(1000),
      )
      .futureValue
    val historyThroughApi = scanClient
      .getUpdateHistory(
        1000,
        None,
        encoding = ProtobufJson,
      )
      .map(ProtobufJsonScanHttpEncodings.httpToLapiUpdate)

    val historyFromStoreWithoutLostData =
      historyFromStore
        .map(UpdateHistoryTestBase.withoutLostData(_, mode = LostInScanApi))
        .map(ScanHttpEncodings.makeConsistentAcrossSvs)

    historyFromStoreWithoutLostData should contain theSameElementsInOrderAs historyThroughApi

    historyThroughApi.headOption.foreach(fromHistory => {
      val fromPointwiseLookup =
        ProtobufJsonScanHttpEncodings.httpToLapiUpdate(
          scanClient.getUpdate(fromHistory.update.update.updateId, encoding = ProtobufJson)
        )
      fromPointwiseLookup shouldBe fromHistory
    })

    succeed
  }

  def compareHistoryViaScanApi(
      ledgerBegin: String,
      svAppBackend: SvAppBackendReference,
      scanClient: ScanAppClientReference,
  ): Assertion = {
    val participant = svAppBackend.participantClient
    val dsoParty = svAppBackend.getDsoInfo().dsoParty

    val updatesFromHistory = updateHistoryFromParticipant(ledgerBegin, dsoParty, participant)
      .map(UpdateHistoryTestBase.withoutLostData(_, mode = LostInScanApi))
      .map(ScanHttpEncodings.makeConsistentAcrossSvs)

    val updatesFromScanApi = scanClient
      .getUpdateHistory(
        updatesFromHistory.size,
        Some(
          (
            0L,
            // The after0 argument to getUpdates() is exclusive, so we need to subtract a small value
            // to include the first element
            updatesFromHistory.head.update.recordTime.addMicros(-1L).toString,
          )
        ),
        encoding = CompactJson,
      )
      .map(CompactJsonScanHttpEncodings.httpToLapiUpdate)
      .map(_.update)

    updatesFromScanApi should have length updatesFromHistory.size.toLong

    def responseDomainId(update: GetTreeUpdatesResponse): String = update.domainId.toProtoPrimitive
    val recordedUpdatesByDomainId = updatesFromScanApi.groupBy(responseDomainId)
    val actualUpdatesByDomainId = updatesFromHistory.groupBy(responseDomainId)

    recordedUpdatesByDomainId.keySet should be(actualUpdatesByDomainId.keySet)
    recordedUpdatesByDomainId.keySet.foreach { domainId =>
      clue(s"Comparing updates for domain $domainId") {
        val actualForDomain = actualUpdatesByDomainId.get(domainId).value
        val recordedForDomain = recordedUpdatesByDomainId.get(domainId).value
        actualForDomain.length shouldBe recordedForDomain.length
        actualForDomain.zip(recordedForDomain).foreach { case (actual, recorded) =>
          actual shouldBe dropTrailingEmptyOptionals(recorded)
        }
      }
    }

    updatesFromScanApi.headOption.foreach(fromHistory => {
      val fromPointwiseLookup =
        CompactJsonScanHttpEncodings.httpToLapiUpdate(
          scanClient.getUpdate(fromHistory.update.updateId, encoding = CompactJson)
        )
      fromPointwiseLookup.update shouldBe fromHistory
    })

    succeed
  }

  // Minimal, human-readable description of an update
  def shortDebugDescription(e: definitions.TreeEvent): String = e match {
    case definitions.TreeEvent.members.CreatedEvent(http) =>
      s"Created(${http.templateId}, ${http.contractId})"
    case definitions.TreeEvent.members.ExercisedEvent(http) =>
      s"Exercised(${http.choice}, ${http.contractId})"
  }
  def shortDebugDescription(u: definitions.UpdateHistoryItem): String = {
    u match {
      case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(http) =>
        s"Transaction(${http.updateId}, ${http.recordTime}, '${http.workflowId}', ${http.rootEventIds
            .map(i => shortDebugDescription(http.eventsById(i)))
            .mkString(", ")})"
      case definitions.UpdateHistoryItem.members.UpdateHistoryReassignment(http) =>
        s"Reassignment(${http.updateId}, ${http.recordTime})"
    }
  }
  def shortDebugDescription(u: Seq[definitions.UpdateHistoryItem]): String = {
    u.map(shortDebugDescription).mkString("[\n", ",\n", "\n]")
  }
}
