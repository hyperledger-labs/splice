package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp

import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.digitalasset.canton.{SynchronizerAlias, HasActorSystem, HasExecutionContext}

import scala.concurrent.duration.*

class UpdateHistoryIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with SplitwellTestUtil
    with TimeTestUtil
    with HasActorSystem
    with HasExecutionContext
    with UpdateHistoryTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
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
    // Note: using `def`, as the trigger may be destroyed and recreated
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
          getSingleAppPaymentRequest(aliceWalletClient)
        },
      )

    actAndCheck(
      "alice initiates payment accept request on global domain",
      aliceWalletClient.acceptAppPaymentRequest(paymentRequest.contractId),
    )(
      "alice sees balance update on splitwell domain",
      _ =>
        inside(aliceSplitwellClient.listBalanceUpdates(key)) { case Seq(update) =>
          val synchronizerId = aliceValidatorBackend.participantClient.synchronizers.id_of(
            SynchronizerAlias.tryCreate("splitwell")
          )
          aliceValidatorBackend.participantClient.ledger_api_extensions.acs
            .lookup_contract_domain(
              aliceUserParty,
              Set(update.contractId.contractId),
            ) shouldBe Map(
            update.contractId.contractId -> synchronizerId.logical
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

      eventually() {
        checkUpdateHistoryMetrics(sv1ScanBackend, sv1ScanBackend.participantClient, dsoParty)
      }
    }
  }

}
