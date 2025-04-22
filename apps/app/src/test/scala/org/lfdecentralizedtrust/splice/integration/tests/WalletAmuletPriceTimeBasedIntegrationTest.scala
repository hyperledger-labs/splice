package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp

class WalletAmuletPriceTimeBasedIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
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
