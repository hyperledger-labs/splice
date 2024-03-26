package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletAmuletPriceTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAmuletPrice(2)

  "A wallet with amulet price 2.0" should {
    "see round with amulet price 2.0" in { implicit env =>
      // Eventually to make sure we wait until Scan has ingested the round.
      eventually() {
        sv1ScanBackend
          .getTransferContextWithInstances(CantonTimestamp.now())
          .latestOpenMiningRound
          .contract
          .payload
          .amuletPrice shouldBe BigDecimal(
          2
        ).bigDecimal.setScale(10)
      }
    }

  }
}
