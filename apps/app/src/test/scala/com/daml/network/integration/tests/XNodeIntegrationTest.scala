package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{SvTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class XNodeIntegrationTest extends CNNodeIntegrationTest with SvTestUtil with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyX(this.getClass.getSimpleName)

  "tap on X nodes" in { implicit env =>
    onboardWalletUser(aliceWallet, aliceValidator)
    aliceWallet.tap(1000)
  }
}
