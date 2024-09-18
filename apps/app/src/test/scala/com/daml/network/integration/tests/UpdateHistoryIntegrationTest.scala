package com.daml.network.integration.tests

import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.network.codegen.java.splice.wallet.payment as walletCodegen
import com.daml.network.config.ConfigTransforms
import com.daml.network.config.ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.console.{
  ParticipantClientReference,
  ScanAppBackendReference,
  ScanAppClientReference,
}
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  Reassignment,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.http.v0.definitions
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.scan.admin.http.LosslessScanHttpEncodings
import com.daml.network.util.*
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.daml.network.store.{
  PageLimit,
  TreeUpdateWithMigrationId,
  UpdateHistory,
  UpdateHistoryTestBase,
}
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.{
  AssignedWrapper,
  TransactionTreeWrapper,
  UnassignedWrapper,
}
import com.digitalasset.canton.topology.DomainId

import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import org.scalatest.Assertion

import scala.concurrent.duration.*

class UpdateHistoryIntegrationTest
    extends IntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with SplitwellTestUtil
    with TimeTestUtil
    with HasActorSystem
    with HasExecutionContext
    with UpdateHistoryComparator {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-current.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      .withTrafficTopupsDisabled

  "update history can replicate update stream" in { implicit env =>
    val ledgerBeginSv1 = sv1Backend.participantClient.ledger_api.state.end()
    val ledgerBeginAlice = aliceValidatorBackend.participantClient.ledger_api.state.end()
    val tapAmount = com.digitalasset.daml.lf.data.Numeric.assertFromString("33." + "3".repeat(10))
    val transferAmount =
      com.digitalasset.daml.lf.data.Numeric.assertFromString("11." + "1".repeat(10))

    // The trigger that advances rounds, running in the sv app
    // Note: using `def`, as the trigger may be destroyed and recreated (when the sv delegate changes)
    def advanceRoundsTrigger = sv1Backend.dsoDelegateBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

    val (aliceUserParty, bobUserParty, _, _, key, _) = initSplitwellTest()

    actAndCheck(
      "Tap amulets for Alice and Bob", {
        aliceWalletClient.tap(tapAmount)
        bobWalletClient.tap(tapAmount)
      },
    )(
      "Amulets should appear in Alice and Bob's wallet",
      _ => {
        aliceWalletClient.list().amulets should have length 1
        bobWalletClient.list().amulets should have length 1
      },
    )
    clue("Transfer some CC to alice") {
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, transferAmount)
    }

    actAndCheck(
      "Transfer some CC to alice, to commit transactions related to CC transfers",
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, transferAmount),
    )(
      "Alice receives the transfer from bob and merges amulets",
      _ => {
        val partitionAmount = walletUsdToAmulet(tapAmount) + transferAmount / 2
        aliceWalletClient.balance().unlockedQty should be > partitionAmount
        bobWalletClient.balance().unlockedQty should be < partitionAmount

        aliceWalletClient.list().amulets should have length 1
        bobWalletClient.list().amulets should have length 1
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
            new walletCodegen.ReceiverAmuletAmount(
              bobUserParty.toProtoPrimitive,
              transferAmount.bigDecimal,
            )
          ),
        ),
      )(
        "alice sees payment request on global domain",
        _ => {
          getSingleRequestOnDecentralizedSynchronizer(aliceWalletClient)
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
      // History for the DSO, read from SV1 (only contains transactions on the global domain)
      // Using eventually(), as we don't know when UpdateHistory has caught up with the updates
      eventually() {
        compareHistory(
          sv1Backend.participantClient,
          sv1ScanBackend.appState.store.updateHistory,
          ledgerBeginSv1,
        )
      }
      eventually() {
        val scanClient = scancl("sv1ScanClient")
        compareHistoryViaScanApi(
          ledgerBeginSv1,
          sv1Backend,
          scanClient,
        )
        compareHistoryViaLosslessScanApi(
          sv1ScanBackend,
          scanClient,
        )
      }
      // History for Alice, read from aliceValidator (should contain domain transfer because of splitwell)
      eventually() {
        compareHistory(
          aliceValidatorBackend.participantClient,
          aliceValidatorBackend.appState.walletManager
            .getOrElse(throw new RuntimeException(s"Wallet is disabled"))
            .lookupUserWallet(aliceWalletClient.config.ledgerApiUser)
            .futureValue
            .getOrElse(throw new RuntimeException("Alice wallet should exist"))
            .store
            .updateHistory,
          ledgerBeginAlice,
          true,
        )
      }
      eventually() {
        compareHistory(
          sv1Backend.participantClient,
          sv1Backend.appState.svStore.updateHistory,
          ledgerBeginSv1,
        )
      }
      eventually() {
        compareHistory(
          sv1Backend.participantClient,
          sv1Backend.appState.dsoStore.updateHistory,
          ledgerBeginSv1,
        )
      }
      eventually() {
        compareHistory(
          aliceValidatorBackend.participantClient,
          aliceValidatorBackend.appState.store.updateHistory,
          ledgerBeginAlice,
        )
      }
    }
  }

  private def compareHistoryViaLosslessScanApi(
      scanBackend: ScanAppBackendReference,
      scanClient: ScanAppClientReference,
  ) = {
    val historyFromStore = scanBackend.appState.store.updateHistory
      .getUpdates(
        None,
        PageLimit.tryCreate(1000),
      )
      .futureValue
    val historyThroughApi = scanClient
      .getUpdateHistory(
        1000,
        None,
        true,
      )
      .map {
        case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(http) =>
          LosslessScanHttpEncodings.httpToLapiTransaction(http)
        case definitions.UpdateHistoryItem.members.UpdateHistoryReassignment(_) =>
          // TODO(#14067): Support decoding reasssignments, and test this also in the soft migration test where we actually have them
          fail("Unexpected reassignment")
      }

    val historyFromStoreWithoutLostData = historyFromStore.map {
      case TreeUpdateWithMigrationId(update, migrationId) =>
        TreeUpdateWithMigrationId(
          UpdateHistoryTestBase.withoutLostData(update, forBackfill = true),
          migrationId,
        )
    }

    historyFromStoreWithoutLostData should contain theSameElementsInOrderAs historyThroughApi
  }

  private def compareHistory(
      participant: ParticipantClientReference,
      updateHistory: UpdateHistory,
      ledgerBegin: ParticipantOffset,
      mustIncludeReassignments: Boolean = false,
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
      }

    val recordedUpdates = updateHistory
      .getUpdates(
        Some(
          (
            0L,
            // Note that we deliberately do not start from ledger begin here since the ledgerBeginSv1 variable above
            // only points at the end after initialization.
            actualUpdates.head.update.recordTime
              .minusMillis(1L), // include the first element, as otherwise it's excluded
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
    val actualUpdatesWithoutLostData = actualUpdates.map(UpdateHistoryTestBase.withoutLostData(_))
    val recordedUpdatesWithoutLostData = recordedUpdates.map(_.update)
    actualUpdatesWithoutLostData should contain theSameElementsInOrderAs recordedUpdatesWithoutLostData
  }
}
