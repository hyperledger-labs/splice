package com.daml.network.integration.tests

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, TransactionTree, TreeEvent}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.config.CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.console.CNParticipantClientReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  Reassignment,
  ReassignmentEvent,
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
import com.digitalasset.canton.admin.api.client.commands.LedgerApiV2Commands.UpdateService.{
  AssignedWrapper,
  TransactionTreeWrapper,
  UnassignedWrapper,
}
import com.digitalasset.canton.topology.DomainId

import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.scalatest.Assertion

import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

class UpdateHistoryIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with SplitwellTestUtil
    with TimeTestUtil
    with HasActorSystem
    with HasExecutionContext {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
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
    val ledgerBeginSv1 = sv1Backend.participantClient.ledger_api.state.end()
    val ledgerBeginAlice = aliceValidatorBackend.participantClient.ledger_api.state.end()
    val tapAmount = com.daml.lf.data.Numeric.assertFromString("33." + "3".repeat(10))
    val transferAmount = com.daml.lf.data.Numeric.assertFromString("11." + "1".repeat(10))

    // The trigger that advances rounds, running in the sv app
    // Note: using `def`, as the trigger may be destroyed and recreated (when the sv leader changes)
    def advanceRoundsTrigger = sv1Backend.leaderBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

    val (aliceUserParty, bobUserParty, _, _, key, _) = initSplitwellTest()

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
        val partitionAmount = walletUsdToCoin(tapAmount) + transferAmount / 2
        aliceWalletClient.balance().unlockedQty should be > partitionAmount
        bobWalletClient.balance().unlockedQty should be < partitionAmount

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

    val (_, paymentRequest) =
      actAndCheck(timeUntilSuccess = 40.seconds, maxPollInterval = 1.second)(
        "alice initiates transfer on splitwell domain",
        aliceSplitwellClient.initiateTransfer(
          key,
          Seq(
            new walletCodegen.ReceiverCCAmount(
              bobUserParty.toProtoPrimitive,
              transferAmount.bigDecimal,
            )
          ),
        ),
      )(
        "alice sees payment request on global domain",
        _ => {
          getSingleRequestOnGlobalDomain(aliceWalletClient)
        },
      )

    actAndCheck(
      "alice initiates payment accept request on global domain",
      aliceWalletClient.acceptAppPaymentRequest(paymentRequest.contractId),
    )(
      "alice sees balance update on splitwell domain",
      _ =>
        inside(aliceSplitwellClient.listBalanceUpdates(key)) { case Seq(update) =>
          val domainId = aliceValidatorBackend.participantClient.domains.id_of(
            DomainAlias.tryCreate("splitwell")
          )
          aliceValidatorBackend.participantClient.ledger_api_extensions.acs
            .lookup_contract_domain(
              aliceUserParty,
              Set(update.contractId.contractId),
            ) shouldBe Map(
            update.contractId.contractId -> domainId
          )
        },
    )

    clue("Update history is consistent with update stream") {
      // History for the SVC, read from SV1 (only contains transactions on the global domain)
      // Using eventually(), as we don't know when UpdateHistory has caught up with the updates
      eventually() {
        compareHistory(
          sv1Backend.participantClient,
          sv1ScanBackend.appState.store.updateHistory
            .getOrElse(throw new RuntimeException("Scan should have history")),
          ledgerBeginSv1,
        )
      }
      // History for Alice, read from aliceValidator (should contain domain transfer because of splitwell)
      eventually() {
        compareHistory(
          aliceValidatorBackend.participantClient,
          aliceValidatorBackend.appState.walletManager
            .lookupUserWallet(aliceWalletClient.config.ledgerApiUser)
            .getOrElse(throw new RuntimeException("Alice wallet should exist"))
            .store
            .updateHistory
            .getOrElse(throw new RuntimeException("User wallet should have history")),
          ledgerBeginAlice,
        )
      }

    }
  }

  private def compareHistory(
      participant: CNParticipantClientReference,
      updateHistory: UpdateHistory,
      ledgerBegin: ParticipantOffset,
  ): Assertion = {
    val ledgerEnd = participant.ledger_api.state.end()

    val actualUpdates = participant.ledger_api.updates
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

  private def withoutLostData(response: GetTreeUpdatesResponse): GetTreeUpdatesResponse = {
    response match {
      case GetTreeUpdatesResponse(TransactionTreeUpdate(tree), domain) =>
        GetTreeUpdatesResponse(TransactionTreeUpdate(withoutLostData(tree)), domain)
      case GetTreeUpdatesResponse(ReassignmentUpdate(transfer), domain) =>
        GetTreeUpdatesResponse(ReassignmentUpdate(withoutLostData(transfer)), domain)
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
      /*recordTime = */ tree.getRecordTime,
    )
  }

  private def withoutLostData(event: TreeEvent): TreeEvent = {
    event match {
      case created: CreatedEvent =>
        withoutLostData(created)
      case exercised: ExercisedEvent =>
        withoutLostData(exercised)
      case _ => throw new RuntimeException("Invalid event type")
    }
  }

  private def withoutLostData(created: CreatedEvent): CreatedEvent = {
    new CreatedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(), // Not preserved
      /*eventId = */ created.getEventId,
      /*templateId = */ created.getTemplateId,
      /* packageName = */ created.getPackageName,
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
  }

  private def withoutLostData(exercised: ExercisedEvent): ExercisedEvent = {
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
  }

  private def withoutLostData(
      transfer: Reassignment[ReassignmentEvent]
  ): Reassignment[ReassignmentEvent] = {
    transfer match {
      case Reassignment(updateId, offset, recordTime, assign: Assign) =>
        Reassignment(
          updateId,
          offset,
          recordTime,
          assign.copy(
            createdEvent = withoutLostData(assign.createdEvent)
          ),
        )
      case Reassignment(updateId, offset, recordTime, unassign: Unassign) =>
        Reassignment(
          updateId,
          offset,
          recordTime,
          unassign,
        )
      case _ => throw new RuntimeException("Invalid transfer type")
    }
  }

}
