package com.daml.network.integration.tests

import com.daml.network.config.CoinConfigTransforms
import com.daml.network.integration.tests.CoinTests.{CoinTestConsoleEnvironment}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext

class WalletCoinPriceIntegrationTest
    extends CoinIntegrationTest
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => CoinConfigTransforms.setCoinPrice(2)(conf))

  "A wallet with coin price 2.0" should {
    "see round with coin price 2.0" in { implicit env =>
      // Eventually to make sure we wait until Scan has ingested the round.
      eventually() {
        scan.getTransferContext().latestOpenMiningRound.value.payload.coinPrice shouldBe BigDecimal(
          2
        ).bigDecimal.setScale(10)
      }
    }

  }
}
