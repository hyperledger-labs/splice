package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletNoDevNetFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.noDevNet(config))

  "A wallet UI when isDevNet=false" should {

    "not show the dev-net buttons" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidatorBackend)

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice logs in", {
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees everything...",
          _ => {
            // check that everything is loaded
            find(id("tx-history")).valueOrFail("Not yet loaded.")
            find(id("transfer-offers")).valueOrFail("Not yet loaded.")
          },
        )
        clue("...except the devnet buttons") {
          // no need to retry these checks
          find(id("tap-button")) should be(None)
          find(id("self-feature")) should be(None)
        }
      }
    }

  }
}
