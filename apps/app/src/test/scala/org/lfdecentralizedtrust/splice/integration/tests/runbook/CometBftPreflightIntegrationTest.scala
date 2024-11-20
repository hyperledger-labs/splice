package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import java.net.{InetSocketAddress, Socket}
import scala.util.Using

/** Preflight test that makes sure that the cometBFT nodes of *our* SVs (1-4) have initialized fine.
  */
class CometBftPreflightIntegrationTest extends IntegrationTestWithSharedEnvironment {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "p2p port for all CometBft nodes is accessible" in { env =>
    env.svs.remote.zipWithIndex.map { case (_, index) =>
      val cometBftP2pHost = sys.env("NETWORK_APPS_ADDRESS")
      val port = s"26$migrationId${index + 1}6".toInt
      clue(s"Connection to $cometBftP2pHost with port $port") {
        // All we care about is the p2p port for CometBFT being accessible by other nodes
        // The socket connects in the constructor, therefore if no error is thrown during the initialization then a successful TCP connection is established
        eventuallySucceeds() {
          Using.resource(new Socket()) { socket =>
            socket.connect(new InetSocketAddress(cometBftP2pHost, port), 1000)
          }
        }
      }
    }
  }

  "All svs have their CometBFT nodes set as validators" in { env =>
    for (i <- 1 to 4) {
      val sv = env.svs.remote.find(sv => sv.name == s"sv$i").value
      eventuallySucceeds() {
        sv.cometBftNodeStatus().votingPower.doubleValue should be(1d)
      }
    }
  }

}
