package org.lfdecentralizedtrust.splice.integration.tests.connectivity

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.toxiproxy.UseToxiproxy
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.console.CommandFailure
import monocle.macros.syntax.lens.*

class WalletAppConnectivityIntegrationTest extends IntegrationTest with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, config) =>
          // we want fine-grained control when we send a AmuletOperation from the wallet & query the scan app
          ConfigTransforms.updateAllAutomationConfigs(
            _.focus(_.enableAutomaticRewardsCollectionAndAmuletMerging).replace(false)
          )(config),
        (_, config) =>
          ConfigTransforms.updateAllScanAppConfigs_(
            // This avoids the values being cached for so long that we never try to fetch the AmuletRules
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

    // aliceWalletClient.list().amulets contains one tap(3) and 0 or more tap(2) as it retries until connection fails.
    forExactly(1, aliceWalletClient.list().amulets) { amulet =>
      BigDecimal(amulet.contract.payload.amount.initialAmount) shouldBe walletUsdToAmulet(3)
    }

  }
}
