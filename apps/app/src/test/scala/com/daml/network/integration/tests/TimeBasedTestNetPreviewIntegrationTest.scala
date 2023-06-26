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
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.noDevNet(config))

  "TestNet initializes correctly" in { implicit env =>
    clue("SVC contains 4 SV") {
      Seq(sv1, sv2, sv3, sv4).map(_.getSvcInfo().svcRules.payload.members should have size 4)
    }

    val sv1Party = onboardWalletUser(sv1Wallet, sv1Validator)
    val bobParty = onboardWalletUser(bobWallet, bobValidator)

    actAndCheck(
      "Advance round",
      advanceRoundsByOneTick,
    )(
      "Wait for SV rewards to be collected",
      _ => {
        advanceTimeByPollingInterval(sv1)
        sv1Wallet.balance().unlockedQty should be > BigDecimal(0)
      },
    )

    val sv1Balance = sv1Wallet.balance().unlockedQty
    val amountToTransfer = 1

    actAndCheck(
      "Transfer from SV1 wallet to Bob wallet",
      p2pTransfer(sv1Validator, sv1Wallet, bobWallet, bobParty, amountToTransfer),
    )(
      "Check balances",
      _ => {
        checkWallet(bobParty, bobWallet, Seq((amountToTransfer, amountToTransfer)))
        checkWallet(
          sv1Party,
          sv1Wallet,
          Seq((sv1Balance - amountToTransfer - smallAmount, sv1Balance - amountToTransfer)),
        )
      },
    )
  }

}
