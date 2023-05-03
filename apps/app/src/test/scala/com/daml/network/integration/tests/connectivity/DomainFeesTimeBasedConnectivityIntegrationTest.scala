package com.daml.network.integration.tests.connectivity

import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs_
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{DomainFeesConstants, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

import java.time.Duration

class DomainFeesTimeBasedConnectivityIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withHttpSettingsForHigherThroughput
      .addConfigTransform((_, cnNodeConfig) =>
        updateAllValidatorConfigs_(validatorConfig =>
          validatorConfig
            .focus(_.treasury.enableValidatorTrafficBalanceChecks)
            .replace(true)
            .focus(_.automation.enableAutomaticValidatorTrafficBalanceTopup)
            .replace(true)
        )(cnNodeConfig)
      )
  }

  private val toxiproxy = UseToxiproxy(createScanLedgerApiProxy = true)
  registerPlugin(toxiproxy)

  "A properly configured validator traffic top-up loop" should {
    "ignore stale traffic balance information from the scan app" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        clue("Disconnect scan app from the ledger")(
          toxiproxy.disableConnectionViaProxy(UseToxiproxy.scanLedgerApiProxyName)
        ),
        entries => {
          // Check that the scan app's ledger connection is disabled
          forAtLeast(1, entries)(
            _.message should include("UNAVAILABLE: io exception")
          )
        },
      )

      clue("Verify that the scan app API is still up")(
        scan.getValidatorTrafficBalance(aliceValidator.getValidatorPartyId()).totalPaid shouldBe 0
      )

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        {
          clue("Give the validator sufficient coins") {
            aliceValidatorWallet.tap(1000)
          }
          clue("Advance time to trigger top-up loop a few times") {
            val minTopupWaitTime =
              Duration.ofMillis((DomainFeesConstants.minTopupWaitTime.value * 1e3).toLong)
            advanceTime(minTopupWaitTime)
            advanceTime(minTopupWaitTime)
            advanceTime(minTopupWaitTime)
          }
        },
        entries => {
          // Check that top up happens exactly once even though scan app reports that we should top up
          forExactly(1, entries)(
            _.message should include("successfully bought extra traffic")
          )
        },
      )

    }
  }
}
