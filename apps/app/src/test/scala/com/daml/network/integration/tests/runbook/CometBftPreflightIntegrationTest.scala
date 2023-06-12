package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.net.Socket

/** Preflight test that makes sure that the cometBFT nodes of *our* SVs (1-4) have initialized fine.
  */
class CometBftPreflightIntegrationTest extends CNNodeIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  // Ignored as the CI preflight tests still use jsonnet
  "p2p port for SV1 CometBft node is accessible" ignore { _ =>
    val cometBftP2pUrl = s"sv-1.svc.${sys.env("NETWORK_APPS_ADDRESS")}";
    // All we care about is the p2p port for CometBFT being accessible by other nodes
    // The socket connects in the constructor, therefore if no error is thrown during the initialization then a successful TCP connection is established
    val socket = new Socket(cometBftP2pUrl, 26656)
    socket.close()
  }
}
