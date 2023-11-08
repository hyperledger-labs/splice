package com.daml.network.integration.tests.connectivity

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

class WalletAppConnectivityIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, config) =>
          // we want fine-grained control when we send a CoinOperation from the wallet & query the scan app
          CNNodeConfigTransforms.updateAllAutomationConfigs(
            _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
          )(config),
        (_, config) =>
          CNNodeConfigTransforms.updateAllScanAppConfigs_(
            // This avoids the values being cached for so long that we never try to fetch the CoinRules
            _.copy(miningRoundsCacheTimeToLiveOverride =
              Some(NonNegativeFiniteDuration.ofSeconds(5))
            )
          )(config),
      )

  private val toxiproxy = UseToxiproxy(createScanAppProxies = true)
  registerPlugin(toxiproxy)

  "wallet should recover after a disconnect from the Scan HTTP API" in { implicit env =>
    val (_, _) = onboardAliceAndBob()

    // we deliberately retry tap(2) until it fails because there could be cached results.
    actAndCheck(
      "disable proxy connection",
      toxiproxy.disableConnectionViaProxy(
        UseToxiproxy.scanHttpApiProxyName(aliceValidatorBackend.name)
      ),
    )(
      "tapping on alice wallet should fail",
      _ => {
        loggerFactory.assertThrowsAndLogsSeq[CommandFailure](
          aliceWalletClient.tap(2),
          entries => {
            forAtLeast(1, entries)(
              _.message should include(
                "failed because of java.net.ConnectException: Connection refused"
              )
            )
          },
        )
      },
    )

    actAndCheck(
      "enable back proxy connection",
      toxiproxy.enableConnectionViaProxy(
        UseToxiproxy.scanHttpApiProxyName(aliceValidatorBackend.name)
      ),
    )(
      "tapping on alice wallet should work",
      _ => {
        aliceWalletClient.tap(3)
      },
    )

    // aliceWalletClient.list().coins contains one tap(3) and 0 or more tap(2) as it retries until connection fails.
    forExactly(1, aliceWalletClient.list().coins) { coin =>
      coin.contract.payload.amount.initialAmount.longValue() shouldBe 3L
    }

  }
}
