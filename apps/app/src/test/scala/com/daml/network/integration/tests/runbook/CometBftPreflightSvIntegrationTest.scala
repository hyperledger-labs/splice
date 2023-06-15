package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.net.Socket

/** Preflight test that makes sure that the cometBFT node of the node deployed through sv-runbook has initialized fine.
  */
class CometBftPreflightSvIntegrationTest extends CNNodeIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "p2p port for the CometBft node is accessible" in { _ =>
    val cometBftP2pUrl = s"cometbft.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}";
    // All we care about is the p2p port for CometBFT being accessible by other nodes
    // The socket connects in the constructor, therefore if no error is thrown during the initialization then a successful TCP connection is established
    val socket = new Socket(cometBftP2pUrl, 26696)
    socket.close()
  }

}
