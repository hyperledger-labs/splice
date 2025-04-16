package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}

class WalletNoDevNetFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.noDevNet(config))
      // disable top-ups since in non-devnet setups, validators need to pay for top-ups
      .withTrafficTopupsDisabled

  "A wallet UI when isDevNet=false" should {

    "not show the dev-net buttons" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

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
