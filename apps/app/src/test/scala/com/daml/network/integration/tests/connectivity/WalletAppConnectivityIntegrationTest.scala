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
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

import scala.concurrent.Future

class WalletAppConnectivityIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        // we want fine-grained control when we send a CoinOperation from the wallet & query the scan app
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
        )(config)
      )

  private val toxiproxy = UseToxiproxy(createScanAppProxies = true)
  registerPlugin(toxiproxy)

  "wallet should recover after a short disconnect from the Scan HTTP API" in { implicit env =>
    val (_, _) = onboardAliceAndBob()

    toxiproxy.disableConnectionViaProxy(UseToxiproxy.scanHttpApiProxyName(aliceWalletBackend.name))
    val tapFuture =
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        Future(aliceWallet.tap(1))(env.executionContext),
        entries => {
          forAtLeast(
            1,
            entries,
          )(
            _.message should include regex (
              "operation 'execute coin operation batch' failed .*ConnectionRefused"
            )
          )
        },
      )

    toxiproxy.enableConnectionViaProxy(UseToxiproxy.scanHttpApiProxyName(aliceWalletBackend.name))

    tapFuture.value // tap eventually succeeds
    eventually() { // ... and we see the coin it creates
      aliceWallet.list().coins should have length 1
    }

  }

  "wallet should recover after a longer disconnect from the Scan HTTP API" in { implicit env =>
    val (_, _) = onboardAliceAndBob()

    toxiproxy.disableConnectionViaProxy(UseToxiproxy.scanHttpApiProxyName(aliceWalletBackend.name))

    loggerFactory.assertThrowsAndLogs[CommandFailure](
      aliceWallet.tap(2),
      // triggered after 20s by Akka HTTP timeout.
      // TODO(#3633): better error handling.
      _.errorMessage should include("Unsupported Content-Type [Some(text/plain; charset=UTF-8)]"),
    )

    toxiproxy.enableConnectionViaProxy(UseToxiproxy.scanHttpApiProxyName(aliceWalletBackend.name))

    aliceWallet.tap(3)
    eventually() { // Note that we check that we see 2 coins in the wallet, because we retry the `StreamTcpException`
      // we receive indefinitely,  so the `tap(2)` that we started earlier and saw fail due to the Akka HTTP timeout
      // eventually succeeds once we re-establish the connection via the proxies.
      aliceWallet.list().coins should have length 2
    }

  }
}
