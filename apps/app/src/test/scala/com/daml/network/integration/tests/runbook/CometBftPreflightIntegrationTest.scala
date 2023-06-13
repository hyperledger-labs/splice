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

  "p2p port for all CometBft nodes is accessible" ignore { env =>
    env.svs.remote.map { svs =>
      val cometBftP2pUrl = s"${svs.name}.svc.${sys.env("NETWORK_APPS_ADDRESS")}";
      // All we care about is the p2p port for CometBFT being accessible by other nodes
      // The socket connects in the constructor, therefore if no error is thrown during the initialization then a successful TCP connection is established
      val socket = new Socket(cometBftP2pUrl, 26656)
      socket.close()
    }
  }

}
