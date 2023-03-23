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

class WalletCoinPriceTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withCoinPrice(2)

  "A wallet with coin price 2.0" should {
    "see round with coin price 2.0" in { implicit env =>
      // Eventually to make sure we wait until Scan has ingested the round.
      eventually() {
        scan
          .getTransferContextWithInstances(CantonTimestamp.now())
          .latestOpenMiningRound
          .payload
          .coinPrice shouldBe BigDecimal(
          2
        ).bigDecimal.setScale(10)
      }
    }

  }
}
