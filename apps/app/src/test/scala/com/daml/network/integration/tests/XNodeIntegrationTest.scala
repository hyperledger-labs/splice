package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{SvTestUtil, WalletTestUtil}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class XNodeIntegrationTest extends CNNodeIntegrationTest with SvTestUtil with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyX(this.getClass.getSimpleName)

  "tap on X nodes" in { implicit env =>
    // TODO(#4974) Make sure all of them get properly initialized
    sv1.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
    sv2.sequencerNodeStatus() should matchPattern { case NodeStatus.NotInitialized(_) => }
    sv3.sequencerNodeStatus() should matchPattern { case NodeStatus.NotInitialized(_) => }
    sv4.sequencerNodeStatus() should matchPattern { case NodeStatus.NotInitialized(_) => }

    onboardWalletUser(aliceWallet, aliceValidator)
    aliceWallet.tap(1000)
  }
}
