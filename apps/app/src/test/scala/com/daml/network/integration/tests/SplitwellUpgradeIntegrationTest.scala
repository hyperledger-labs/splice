package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTestWithSharedEnvironment,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SplitwellUpgradeIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with SplitwellTestUtil
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.useSplitwellUpgradeDomain()(config))

  "splitwell with upgraded domain" should {
    "report both domains" in { implicit env =>
      val splitwellDomains = providerSplitwellBackend.getSplitwellDomainIds()
      splitwellDomains.preferred.uid.id shouldBe "splitwellUpgrade"
      splitwellDomains.others.map(_.uid.id) shouldBe Seq("splitwell")
    }
  }
}
