package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, TransactionTree, TreeEvent}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.*
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.sv.automation.leaderbased.AdvanceOpenMiningRoundTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.daml.network.store.UpdateHistory
import com.digitalasset.canton.admin.api.client.commands.LedgerApiV2Commands.UpdateService.TransactionTreeWrapper
import com.digitalasset.canton.topology.DomainId

import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import org.apache.pekko.stream.scaladsl.{Keep, Sink}

import scala.jdk.CollectionConverters.*

class UpdateHistoryIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil
    with HasActorSystem
    with HasExecutionContext {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransformsToFront(
        { case (_, c) => CNNodeConfigTransforms.ingestFromParticipantBeginInScan(c) }
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppFoundCollectiveConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      .withTrafficTopupsDisabled

  "update history can replicate update stream" in { implicit env =>
    val ledgerBegin = sv1Backend.participantClient.ledger_api.state.end()

    val (aliceUserParty, _) = onboardAliceAndBob()

    val tapAmount = com.daml.lf.data.Numeric.assertFromString("33." + "3".repeat(10))
    val transferAmount = com.daml.lf.data.Numeric.assertFromString("11." + "1".repeat(10))

    // The trigger that advances rounds, running in the sv app
    // Note: using `def`, as the trigger may be destroyed and recreated (when the sv leader changes)
    def advanceRoundsTrigger = sv1Backend.leaderBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

    actAndCheck(
      "Tap coins for Alice and Bob", {
        aliceWalletClient.tap(tapAmount)
        bobWalletClient.tap(tapAmount)
      },
    )(
      "Coins should appear in Alice and Bob's wallet",
      _ => {
        aliceWalletClient.list().coins should have length 1
        bobWalletClient.list().coins should have length 1
      },
    )
    clue("Transfer some CC to alice") {
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, transferAmount)
    }

    actAndCheck(
      "Transfer some CC to alice, to commit transactions related to CC transfers",
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, transferAmount),
    )(
      "Alice receives the transfer from bob and merges coins",
      _ => {
        aliceWalletClient.balance().unlockedQty should be > (tapAmount + transferAmount / 2)
        bobWalletClient.balance().unlockedQty should be < (tapAmount + transferAmount / 2)

        aliceWalletClient.list().coins should have length 1
        bobWalletClient.list().coins should have length 1
      },
    )

    // The current round, as seen by the SV1 scan service (reflects the state of the scan app store)
    def currentRoundInScan(): Long =
      sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

    actAndCheck(
      "Advance one round, to commit transactions related to the round infrastructure", {
        val previousRound = currentRoundInScan()
        // Note: runOnce() does nothing if there is no work to be done.
        eventually() {
          advanceRoundsTrigger.runOnce().futureValue should be(true)
        }
        previousRound
      },
    )(
      "Observe that rounds have advanced once",
      previous => {
        currentRoundInScan() should be(previous + 1)
      },
    )

    clue("Update history is consistent with update stream") {
      val ledgerEnd = sv1Backend.participantClient.ledger_api.state.end()

      val updateHistory: UpdateHistory =
        sv1ScanBackend.appState.store.updateHistory
          .getOrElse(throw new RuntimeException("Scan should have history"))

      val svcParty = sv1ScanBackend.getSvcPartyId()
      svcParty should be(updateHistory.updateStreamParty)

      val actualUpdates = sv1Backend.participantClient.ledger_api.updates
        .trees(
          partyIds = Set(updateHistory.updateStreamParty),
          completeAfter = Int.MaxValue,
          beginOffset = ledgerBegin,
          endOffset = Some(ledgerEnd),
          verbose = false,
        )
        .map {
          case TransactionTreeWrapper(protoTree) =>
            LedgerClient.GetTreeUpdatesResponse(
              TransactionTreeUpdate(LedgerClient.lapiTreeToJavaTree(protoTree)),
              DomainId.tryFromString(protoTree.domainId),
            )
          case _ => throw new RuntimeException("This test should not use domain reassignments")
        }

      val recordedUpdates =
        updateHistory
          .updateStream(ledgerBegin.getAbsolute, ledgerEnd.getAbsolute)
          .toMat(Sink.seq)(Keep.right)
          .run()
          .futureValue

      // Note: UpdateHistory does not preserve all information in updates,
      // so remove fields that are not preserved before comparing.
      val actualUpdatesWithoutLostData = actualUpdates.map(withoutLostData)
      val recordedUpdatesWithoutLostData = recordedUpdates.map(withoutLostData)
      actualUpdatesWithoutLostData should contain theSameElementsInOrderAs recordedUpdatesWithoutLostData
    }
  }

  private def withoutLostData(response: GetTreeUpdatesResponse): GetTreeUpdatesResponse = {
    response match {
      case GetTreeUpdatesResponse(TransactionTreeUpdate(tree), domain) =>
        GetTreeUpdatesResponse(TransactionTreeUpdate(withoutLostData(tree)), domain)
      case GetTreeUpdatesResponse(ReassignmentUpdate(_), _) =>
        // TODO(#10487): Implement reassignments
        throw new RuntimeException("This test should not use domain reassignments")
      case _ => throw new RuntimeException("Invalid update type")
    }
  }

  private def withoutLostData(tree: TransactionTree): TransactionTree = {
    new TransactionTree(
      /*updateId = */ tree.getUpdateId,
      /*commandId = */ "", // Not preserved
      /*workflowId = */ "", // Not preserved
      /*effectiveAt = */ tree.getEffectiveAt,
      /*offset = */ tree.getOffset,
      /*eventsById = */ tree.getEventsById.asScala.view.mapValues(withoutLostData).toMap.asJava,
      /*rootEventIds = */ tree.getRootEventIds,
      /*domainId = */ tree.getDomainId,
      /*traceContext = */ TraceContextOuterClass.TraceContext.getDefaultInstance, // Not preserved
      /*recordTime = */ tree.getEffectiveAt, // TODO(#10656): this is wrong! retrieve the actual record_time from the store

    )
  }

  private def withoutLostData(event: TreeEvent): TreeEvent = {
    event match {
      case created: CreatedEvent =>
        new CreatedEvent(
          /*witnessParties = */ java.util.Collections.emptyList(), // Not preserved
          /*eventId = */ created.getEventId,
          /*templateId = */ created.getTemplateId,
          /* packageName = */ "dummyPackageName", // TODO(#10656): retrieve from store
          /*contractId = */ created.getContractId,
          /*arguments = */ created.getArguments,
          /*createdEventBlob = */ ByteString.EMPTY, // Not preserved
          /*interfaceViews = */ java.util.Collections.emptyMap(), // Not preserved
          /*failedInterfaceViews = */ java.util.Collections.emptyMap(), // Not preserved
          /*contractKey = */ java.util.Optional.empty(), // Not preserved
          /*signatories = */ java.util.Collections.emptyList(), // Not preserved
          /*observers = */ java.util.Collections.emptyList(), // Not preserved
          /*createdAt = */ created.getCreatedAt,
        )
      case exercised: ExercisedEvent =>
        new ExercisedEvent(
          /*witnessParties = */ java.util.Collections.emptyList(), // Not preserved
          /*eventId = */ exercised.getEventId,
          /*templateId = */ exercised.getTemplateId,
          /*interfaceId = */ java.util.Optional.empty(), // Not preserved
          /*contractId = */ exercised.getContractId,
          /*choice = */ exercised.getChoice,
          /*choiceArgument = */ exercised.getChoiceArgument,
          /*actingParties = */ java.util.Collections.emptyList(), // Not preserved
          /*consuming = */ exercised.isConsuming,
          /*childEventIds = */ exercised.getChildEventIds,
          /*exerciseResult = */ exercised.getExerciseResult,
        )
      case _ => throw new RuntimeException("Invalid event type")
    }
  }

}
