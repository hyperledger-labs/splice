package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  ScanAppBackendReference,
  ScanAppClientReference,
  SvAppBackendReference,
}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics.MetricsPrefix
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  LedgerClient,
  Reassignment,
  ReassignmentUpdate,
  TransactionTreeUpdate,
  TreeUpdate,
}
import com.daml.ledger.javaapi.data.*
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
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import com.daml.ledger.api.v2.transaction_filter
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.{
  AssignedWrapper,
  TransactionWrapper,
  UnassignedWrapper,
}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.LocalInstanceReference
import com.digitalasset.canton.metrics.MetricValue
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryResponseItem
import org.scalatest.Assertion

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait UpdateHistoryTestUtil extends TestCommon {

  def updateHistoryFromParticipant(
      beginExclusive: Long,
      partyId: PartyId,
      participant: ParticipantClientReference,
  ): Seq[UpdateHistoryResponse] = {
    val ledgerEnd = participant.ledger_api.state.end()

    val transactionFormat = transaction_filter.TransactionFormat(
      eventFormat = Some(
        transaction_filter.EventFormat(
          filtersByParty = Seq(partyId.toLf -> transaction_filter.Filters(Nil)).toMap,
          filtersForAnyParty = None,
          verbose = false,
        )
      ),
      transactionShape = transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
    )

    participant.ledger_api.updates
      .updates(
        updateFormat = transaction_filter.UpdateFormat(
          includeTransactions = Some(transactionFormat),
          includeReassignments = Some(transactionFormat.getEventFormat),
          includeTopologyEvents = None,
        ),
        completeAfter = PositiveInt.MaxValue,
        beginOffsetExclusive = beginExclusive,
        endOffsetInclusive = Some(ledgerEnd),
      )
      .collect {
        case TransactionWrapper(protoTree) =>
          UpdateHistoryResponse(
            TransactionTreeUpdate(LedgerClient.lapiTreeToJavaTree(protoTree)),
            SynchronizerId.tryFromString(protoTree.synchronizerId),
          )
        case UnassignedWrapper(protoReassignment, Seq(protoUnassignEvent)) =>
          UpdateHistoryResponse(
            ReassignmentUpdate(Reassignment.fromProto(protoReassignment)),
            SynchronizerId.tryFromString(protoUnassignEvent.source),
          )
        case AssignedWrapper(protoReassignment, Seq(protoAssignEvent)) =>
          UpdateHistoryResponse(
            ReassignmentUpdate(Reassignment.fromProto(protoReassignment)),
            SynchronizerId.tryFromString(protoAssignEvent.target),
          )
      }
  }

  def compareHistory(
      participant: ParticipantClientReference,
      updateHistory: UpdateHistory,
      ledgerBegin: Long,
      mustIncludeReassignments: Boolean = false,
  ): Assertion = {
    val actualUpdates =
      updateHistoryFromParticipant(ledgerBegin, updateHistory.updateStreamParty, participant)

    val recordedUpdates = updateHistory
      .getAllUpdates(
        Some(
          (
            0L,
            // The after0 argument to getUpdates() is exclusive, so we need to subtract a small value
            // to include the first element
            actualUpdates.head.update.recordTime.addMicros(-1L),
          )
        ),
        PageLimit.tryCreate(actualUpdates.size),
      )
      .futureValue

    if (mustIncludeReassignments) {
      recordedUpdates.filter(_.update match {
        case UpdateHistoryResponse(ReassignmentUpdate(Reassignment(_, _, _, _: Assign)), _) =>
          true
        case _ => false
      }) should not be empty
      recordedUpdates.filter(_.update match {
        case UpdateHistoryResponse(ReassignmentUpdate(Reassignment(_, _, _, _: Unassign)), _) =>
          true
        case _ => false
      }) should not be empty
    }

    // Note: UpdateHistory does not preserve all information in updates,
    // so remove fields that are not preserved before comparing.
    val actualUpdatesWithoutLostData =
      actualUpdates.map(UpdateHistoryTestBase.withoutLostData(_, mode = LostInStoreIngestion))
    val recordedUpdatesWithoutLostData = recordedUpdates.map(_.update)
    actualUpdatesWithoutLostData should have length recordedUpdatesWithoutLostData.size.longValue()
    actualUpdatesWithoutLostData.zip(recordedUpdatesWithoutLostData).foreach {
      case (actual, recorded) => actual shouldBe recorded
    }
    succeed
  }

  def compareHistoryViaLosslessScanApi(
      scanBackend: ScanAppBackendReference,
      scanClient: ScanAppClientReference,
  ): Assertion = {
    val historyFromStore = scanBackend.appState.automation.updateHistory
      .getAllUpdates(
        None,
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
      ledgerBegin: Long,
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
      .map(dropTrailingNones)

    updatesFromScanApi should have length updatesFromHistory.size.toLong

    def responseSynchronizerId(update: UpdateHistoryResponse): String =
      update.synchronizerId.toProtoPrimitive
    val recordedUpdatesBySynchronizerId = updatesFromScanApi.groupBy(responseSynchronizerId)
    val actualUpdatesBySynchronizerId = updatesFromHistory.groupBy(responseSynchronizerId)

    recordedUpdatesBySynchronizerId.keySet should be(actualUpdatesBySynchronizerId.keySet)
    recordedUpdatesBySynchronizerId.keySet.foreach { synchronizerId =>
      clue(s"Comparing updates for domain $synchronizerId") {
        val actualForDomain = actualUpdatesBySynchronizerId.get(synchronizerId).value
        val recordedForDomain = recordedUpdatesBySynchronizerId.get(synchronizerId).value
        actualForDomain.length shouldBe recordedForDomain.length
        actualForDomain.zip(recordedForDomain).foreach { case (actual, recorded) =>
          actual shouldBe recorded
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

  def checkUpdateHistoryMetrics(
      node: LocalInstanceReference,
      participant: ParticipantClientReference,
      party: PartyId,
  ): Assertion = {
    val expected = updateHistoryFromParticipant(0, party, participant).size

    def getValue(metric: String): Long =
      node.metrics.list(metric).get(metric) match {
        case None => 0
        case Some(_) => node.metrics.get(metric).select[MetricValue.LongPoint].value.value
      }

    val totalMetrics = Seq("transactions", "assignments", "unassignments")
      .map(m => s"$MetricsPrefix.history.updates.$m")
      .map(getValue(_))
      .reduce(_ + _)

    totalMetrics should equal(expected)
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
  def shortDebugDescription(u: TransactionHistoryResponseItem): String = {
    // Minimal, human-readable description.
    // Only contains data that is consistent across SVs (in particular, no offset).
    u.transactionType match {
      case TransactionHistoryResponseItem.TransactionType.members.Transfer =>
        s"Transfer(${u.date}, ${u.transfer.value.sender}, ${u.transfer.value.receivers
            .map(r => s"${r.party} -> ${r.amount}")
            .mkString(", ")})"
      case TransactionHistoryResponseItem.TransactionType.members.Mint =>
        s"Mint(${u.date}, ${u.mint.value.amuletOwner}, ${u.mint.value.amuletAmount})"
      case TransactionHistoryResponseItem.TransactionType.members.DevnetTap =>
        s"DevnetTap(${u.date}, ${u.tap.value.amuletOwner}, ${u.tap.value.amuletAmount})"
      case TransactionHistoryResponseItem.TransactionType.members.AbortTransferInstruction =>
        s"AbortTransferInstruction(${u.date}, ${u.abortTransferInstruction.value.transferInstructionCid})"
    }
  }

  def dropTrailingNones(u: UpdateHistoryResponse): UpdateHistoryResponse =
    u.copy(update = dropTrailingNones(u.update))

  def dropTrailingNones(u: TreeUpdate): TreeUpdate =
    u match {
      case TransactionTreeUpdate(t) => TransactionTreeUpdate(dropTrailingNones(t))
      case ReassignmentUpdate(r) => ReassignmentUpdate(dropTrailingNones(r))
    }

  def dropTrailingNones(t: Transaction): Transaction =
    new Transaction(
      t.getUpdateId,
      t.getCommandId,
      t.getWorkflowId,
      t.getEffectiveAt,
      t.getEvents.asScala.map(dropTrailingNones).asJava,
      t.getOffset,
      t.getSynchronizerId,
      t.getTraceContext,
      t.getRecordTime,
      t.getExternalTransactionHash,
    )

  def dropTrailingNones(r: Reassignment[ReassignmentEvent]): Reassignment[ReassignmentEvent] =
    r.copy(
      event = dropTrailingNones(r.event)
    )

  def dropTrailingNones(e: ReassignmentEvent): ReassignmentEvent =
    e match {
      case unassign: ReassignmentEvent.Unassign => unassign
      case assign: ReassignmentEvent.Assign =>
        assign.copy(createdEvent = dropTrailingNones(assign.createdEvent))
    }

  def dropTrailingNones(e: Event): Event = e match {
    case e: CreatedEvent => dropTrailingNones(e)
    case e: ExercisedEvent => dropTrailingNones(e)
    case _ => fail(s"Unexpected event: $e")
  }

  def dropTrailingNones(e: CreatedEvent): CreatedEvent = new CreatedEvent(
    e.getWitnessParties,
    e.getOffset,
    e.getNodeId,
    e.getTemplateId,
    e.getPackageName,
    e.getContractId,
    dropTrailingNones(e.getArguments),
    e.getCreatedEventBlob,
    e.getInterfaceViews.asScala.view.mapValues(dropTrailingNones(_)).toMap.asJava,
    e.getFailedInterfaceViews,
    e.getContractKey.toScala.map(dropTrailingNones(_)).toJava,
    e.getSignatories,
    e.getObservers,
    e.createdAt,
    e.isAcsDelta,
    e.getRepresentativePackageId,
  )
  def dropTrailingNones(e: ExercisedEvent): ExercisedEvent = new ExercisedEvent(
    e.getWitnessParties,
    e.getOffset,
    e.getNodeId,
    e.getTemplateId,
    e.getPackageName,
    e.getInterfaceId,
    e.getContractId,
    e.getChoice,
    dropTrailingNones(e.getChoiceArgument),
    e.getActingParties,
    e.isConsuming,
    e.getLastDescendantNodeId,
    dropTrailingNones(e.getExerciseResult),
    e.getImplementedInterfaces,
    e.isAcsDelta,
  )

  def dropTrailingNones(v: Value): Value = v match {
    case r: DamlRecord => dropTrailingNones(r)
    case v: Variant => {
      val value = dropTrailingNones(v.getValue())
      v.getVariantId.toScala.fold(new Variant(v.getConstructor, value))(
        new Variant(_, v.getConstructor, value)
      )
    }
    case l: DamlList => DamlList.of(l.toList(dropTrailingNones(_)))
    case o: DamlOptional => DamlOptional.of(o.getValue.toScala.map(dropTrailingNones(_)).toJava)
    case m: DamlGenMap => DamlGenMap.of(m.toMap(identity, dropTrailingNones(_)))
    case m: DamlTextMap => DamlTextMap.of(m.toMap(identity, dropTrailingNones(_)))
    case _ => v
  }

  def dropTrailingNones(r: DamlRecord): DamlRecord = {
    val fields =
      r.getFields()
        .asScala
        .reverse
        .dropWhile(f => f.getValue() == DamlOptional.EMPTY)
        .reverse
        .map { f =>
          val value = dropTrailingNones(f.getValue())
          f.getLabel().toScala.fold(new DamlRecord.Field(value))(new DamlRecord.Field(_, value))
        }
        .asJava
    r.getRecordId.toScala.fold(new DamlRecord(fields))(new DamlRecord(_, fields))
  }
}
