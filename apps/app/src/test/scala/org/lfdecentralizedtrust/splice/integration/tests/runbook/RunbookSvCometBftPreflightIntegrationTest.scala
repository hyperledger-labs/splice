package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment

import java.net.{InetSocketAddress, Socket}
import scala.concurrent.duration.DurationInt
import scala.util.Using

/** Preflight test that makes sure that the cometBFT node of the node deployed through sv-runbook has initialized fine.
  */
class RunbookSvCometBftPreflightIntegrationTest extends IntegrationTestWithSharedEnvironment {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName
    )

  "p2p port for the CometBft node is accessible" in { _ =>
    val cometBftP2pHost = sys.env("NETWORK_APPS_ADDRESS")
    val cometBftPort = 26006 + migrationId.toInt * 100
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
