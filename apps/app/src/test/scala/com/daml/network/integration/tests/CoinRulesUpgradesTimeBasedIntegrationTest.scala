package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil, SplitwellTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import monocle.macros.syntax.lens.*

class CoinRulesUpgradesTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform(CNNodeConfigTransforms.onlySv1)
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs((_, c) =>
          c.copy(enableCoinRulesUpgrade = true)
        )(config)
      )
      // Upgrade all other scan app to support exposing both coinRules versions
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms
          .updateAllScanAppConfigs_(_.copy(enableCoinRulesUpgrade = true))(config)
      )
      // Upgrade validators for Alice, Bob and Splitwell Provider
      .addConfigTransform((_, config) =>
        config
          .focus(_.validatorApps)
          .modify(_.map {
            case (dName, dConfig) if dName.toProtoPrimitive != "sv1Validator" =>
              (dName, dConfig.focus(_.enableCoinRulesUpgrade).replace(true))
            case (dName, dConfig) => (dName, dConfig)
          })
      )
      .withAdditionalSetup(implicit env => {
        aliceValidator.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidator.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })

  "App transfers through upgraded coinRules" in { implicit env =>
    clue("Query both coinRules from scan") {
      sv1Scan.getCoinRules()
      sv1Scan.getCoinRulesV1Test()
    }

    val (alice, bob, _, splitwellProvider, key, _) = initSplitwellTest()

    // We can't currently tap in an upgraded validator, so tapping some coin for sv1 (whose wallet is not upgraded) and transferring it to Alice to use
    // (Direct transfers from an old wallet to a new wallet will go through the old coinRules, thus expected to work)
    val sv1Party = onboardWalletUser(sv1Wallet, sv1Validator)
    sv1Wallet.tap(100)

    actAndCheck(
      "Transfer from SV1's (old) wallet to Alice's (new) wallet",
      p2pTransfer(sv1Validator, sv1Wallet, aliceWallet, alice, 90.0),
    )(
      "Check balances",
      _ => {
        checkWallet(alice, aliceWallet, Seq((90, 90)))
        checkWallet(sv1Party, sv1Wallet, Seq((9, 10)))
      },
    )

    actAndCheck(
      "Transfer from Alice to Bob through Splitwell",
      splitwellTransfer(aliceSplitwell, aliceWallet, bob, BigDecimal(80.0), key),
    )(
      "Check balances",
      _ => {
        checkWallet(alice, aliceWallet, Seq((9, 10)))
        checkWallet(bob, bobWallet, Seq((79, 80)))
      },
    )

    actAndCheck(
      "Advance rounds for rewards to be collectible",
      Range(0, 3).map(_ => advanceRoundsByOneTick),
    )(
      "Provider can still collect rewards with an upgraded validator",
      _ => checkWallet(splitwellProvider, splitwellProviderWallet, Seq((80, 85))),
    )
  }
}
