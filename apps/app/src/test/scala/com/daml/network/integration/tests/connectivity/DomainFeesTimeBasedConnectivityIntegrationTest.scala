package com.daml.network.integration.tests.connectivity

import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs_
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

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
        scan
          .getValidatorTrafficBalance(aliceValidator.getValidatorPartyId())
          .totalPurchased shouldBe 0
      )

      clue("Give the validator sufficient coins")(
        aliceValidatorWallet.tap(1000)
      )

      actAndCheck(
        "Advance time to trigger top-up loop a few times", {
          advanceTimeByMinTopupInterval(aliceValidator)
          advanceTimeByMinTopupInterval(aliceValidator)
          advanceTimeByMinTopupInterval(aliceValidator)
        },
      )(
        // Note that it's possible that top-up may not happen because cutting Scan from the ledger may also
        // cause it to provide stale open mining round information for the top-up. This is a known issue
        // (https://github.com/DACH-NY/canton-network-node/issues/4810) and is only temporary while
        // Scan is also used to mock the validator traffic related endpoints for the Domain Fees PoC
        "Top-up happens at most once even though scan app reports that we should top-up",
        _ => {
          val validatorTraffic =
            aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(ValidatorTraffic.COMPANION)(aliceValidator.getValidatorPartyId())
              .head
          validatorTraffic.data.numPurchases.longValue() should be <= 1L
        },
      )
    }
  }
}
