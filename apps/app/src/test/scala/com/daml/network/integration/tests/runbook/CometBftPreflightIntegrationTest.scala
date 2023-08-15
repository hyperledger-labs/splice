package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.net.Socket

/** Preflight test that makes sure that the cometBFT nodes of *our* SVs (1-4) have initialized fine.
  */
class CometBftPreflightIntegrationTest extends CNNodeIntegrationTestWithSharedEnvironment {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "p2p port for all CometBft nodes is accessible" in { env =>
    env.svs.remote.zipWithIndex.map { case (_, index) =>
      val cometBftP2pHost = sys.env("NETWORK_APPS_ADDRESS")
      val port = 26656 + index * 10
      clue(s"Connection to $cometBftP2pHost with port $port") {
        // All we care about is the p2p port for CometBFT being accessible by other nodes
        // The socket connects in the constructor, therefore if no error is thrown during the initialization then a successful TCP connection is established
        val socket = new Socket(cometBftP2pHost, port)
        socket.close()
      }
    }
  }

  "All svs have their CometBFT nodes set as validators" in { env =>
    for (i <- 1 to 4) {
      val sv = env.svs.remote.find(sv => sv.name == s"sv$i").value
      sv.cometBftNodeStatus().votingPower.doubleValue should be(1d)
    }
  }

}
