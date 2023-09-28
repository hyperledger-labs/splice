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
          CNNodeConfigTransforms.updateAllSvAppFoundCollectiveConfigs_(
            _.copy(initialTickDuration = NonNegativeFiniteDuration.ofSeconds(5))
          )(config),
      )

  private val toxiproxy = UseToxiproxy(createScanAppProxies = true)
  registerPlugin(toxiproxy)

  "wallet should recover after a disconnect from the Scan HTTP API" in { implicit env =>
    val (_, _) = onboardAliceAndBob()

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
        eventually() {
          aliceWalletClient.list().coins should have length 1
        }
      },
    )

  }
}
