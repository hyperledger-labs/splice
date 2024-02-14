package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{SvTestUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class TimeBasedTestNetPreviewIntegrationTest
    extends CNNodeIntegrationTest
    with SvTestUtil
    with TimeTestUtil
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.noDevNet(config))

  "TestNet initializes correctly" in { implicit env =>
    clue("SVC contains 4 SV") {
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map(
        _.getSvcInfo().svcRules.payload.members should have size 4
      )
    }

    val sv1Party = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

    actAndCheck(
      "Advance round",
      advanceRoundsByOneTick,
    )(
      "Wait for SV rewards to be collected",
      _ => {
        sv1WalletClient.balance().unlockedQty should be > BigDecimal(0)
      },
    )

    val sv1Balance = sv1WalletClient.balance().unlockedQty
    val amountToTransfer = 1

    actAndCheck(
      "Transfer from SV1 wallet to Bob wallet",
      p2pTransfer(sv1WalletClient, bobWalletClient, bobParty, amountToTransfer),
    )(
      "Check balances",
      _ => {
        checkWallet(bobParty, bobWalletClient, Seq((amountToTransfer, amountToTransfer)))
        checkWallet(
          sv1Party,
          sv1WalletClient,
          Seq((sv1Balance - amountToTransfer - smallAmount, sv1Balance - amountToTransfer)),
        )
      },
    )
  }

}
