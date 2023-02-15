package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTestWithSharedEnvironment,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletCoinPriceIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => CNNodeConfigTransforms.setCoinPrice(2)(conf))

  "A wallet with coin price 2.0" should {
    "see round with coin price 2.0" in { implicit env =>
      // Eventually to make sure we wait until Scan has ingested the round.
      eventually() {
        scan.getTransferContext().latestOpenMiningRound.payload.coinPrice shouldBe BigDecimal(
          2
        ).bigDecimal.setScale(10)
      }
    }

  }
}
