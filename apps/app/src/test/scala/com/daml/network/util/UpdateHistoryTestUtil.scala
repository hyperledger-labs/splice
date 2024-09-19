package com.daml.network.util

import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.network.console.{
  ParticipantClientReference,
  ScanAppBackendReference,
  ScanAppClientReference,
  SvAppBackendReference,
}
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  Reassignment,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.integration.tests.SpliceTests.TestCommon
import com.daml.network.scan.admin.http.{LosslessScanHttpEncodings, LossyScanHttpEncodings}
import com.daml.network.store.UpdateHistoryTestBase.{LostInScanApi, LostInStoreIngestion}
import com.daml.network.store.{PageLimit, UpdateHistory, UpdateHistoryTestBase}
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.{
  AssignedWrapper,
  TransactionTreeWrapper,
  UnassignedWrapper,
}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import org.scalatest.Assertion

trait UpdateHistoryTestUtil extends TestCommon {

  def updateHistoryFromParticipant(
      beginExclusive: ParticipantOffset,
      partyId: PartyId,
      participant: ParticipantClientReference,
  ): Seq[GetTreeUpdatesResponse] = {
    val ledgerEnd = participant.ledger_api.state.end()

    participant.ledger_api.updates
      .trees(
        partyIds = Set(partyId),
        completeAfter = Int.MaxValue,
        beginOffset = beginExclusive,
        endOffset = Some(ledgerEnd),
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
      ledgerBegin: ParticipantOffset,
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
        true,
      )
      .map(LosslessScanHttpEncodings.httpToLapiUpdate)

    val historyFromStoreWithoutLostData =
      historyFromStore.map(UpdateHistoryTestBase.withoutLostData(_, mode = LostInScanApi))

    historyFromStoreWithoutLostData should contain theSameElementsInOrderAs historyThroughApi
  }

  def compareHistoryViaScanApi(
      ledgerBegin: ParticipantOffset,
      svAppBackend: SvAppBackendReference,
      scanClient: ScanAppClientReference,
  ): Assertion = {
    val participant = svAppBackend.participantClient
    val dsoParty = svAppBackend.getDsoInfo().dsoParty

    val updatesFromHistory = updateHistoryFromParticipant(ledgerBegin, dsoParty, participant)
      .map(UpdateHistoryTestBase.withoutLostData(_, mode = LostInScanApi))

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
        lossless = false,
      )
      .map(LossyScanHttpEncodings.httpToLapiUpdate)
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

    succeed
  }
}
