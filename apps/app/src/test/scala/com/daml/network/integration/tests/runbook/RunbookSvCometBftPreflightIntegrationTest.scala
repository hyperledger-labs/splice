package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.net.{InetSocketAddress, Socket}
import scala.concurrent.duration.DurationInt
import scala.util.Using

/** Preflight test that makes sure that the cometBFT node of the node deployed through sv-runbook has initialized fine.
  */
class RunbookSvCometBftPreflightIntegrationTest extends CNNodeIntegrationTestWithSharedEnvironment {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName
    )

  "p2p port for the CometBft node is accessible" in { _ =>
    val cometBftP2pHost = sys.env("NETWORK_APPS_ADDRESS")
    val cometBftPort = 26096
    // All we care about is the p2p port for CometBFT being accessible by other nodes
    // The socket connects in the constructor, therefore if no error is thrown during the initialization then a successful TCP connection is established
    withClue(s"Testing p2p port at $cometBftP2pHost:$cometBftPort") {
      eventuallySucceeds(timeUntilSuccess = 5.seconds, 1.seconds) {
        Using.resource(new Socket()) { socket =>
          socket.connect(new InetSocketAddress(cometBftP2pHost, cometBftPort), 1000)
        }
      }
    }
  }

  "the CometBFT node is a validator" in { env =>
    val sv = env.svs.remote.find(_.name == "sv").value
    sv.cometBftNodeStatus().votingPower.doubleValue should be(1d)
  }

}
