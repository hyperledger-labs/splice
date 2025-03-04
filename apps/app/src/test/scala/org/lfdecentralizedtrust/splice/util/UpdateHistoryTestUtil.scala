package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  ScanAppBackendReference,
  ScanAppClientReference,
  SvAppBackendReference,
}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics.MetricsPrefix
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  LedgerClient,
  Reassignment,
  ReassignmentUpdate,
  TransactionTreeUpdate,
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
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.{
  AssignedWrapper,
  TransactionTreeWrapper,
  UnassignedWrapper,
}
import com.digitalasset.canton.console.LocalInstanceReference
import com.digitalasset.canton.metrics.MetricValue
import com.digitalasset.canton.topology.{DomainId, PartyId}
import org.scalatest.Assertion

trait UpdateHistoryTestUtil extends TestCommon {
  def updateHistoryFromParticipant(
      beginExclusive: Long,
      partyId: PartyId,
      participant: ParticipantClientReference,
  ): Seq[GetTreeUpdatesResponse] = {
    val ledgerEnd = participant.ledger_api.state.end()

    participant.ledger_api.updates
      .trees(
        partyIds = Set(partyId),
        completeAfter = Int.MaxValue,
        beginOffsetExclusive = beginExclusive,
        endOffsetInclusive = Some(ledgerEnd),
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
}
