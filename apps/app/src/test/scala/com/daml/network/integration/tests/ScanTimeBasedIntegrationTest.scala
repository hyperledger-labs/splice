package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.CoinTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class ScanTimeBasedIntegrationTest extends CoinIntegrationTest with CoinTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  "report correct reference data" in { implicit env =>
    scan.getTransferContext().latestOpenMiningRound.map(_.payload.round.number) shouldBe Some(1)
    advanceRoundsByOneTick
    scan.getTransferContext().latestOpenMiningRound.map(_.payload.round.number) shouldBe Some(2)
  }
}
