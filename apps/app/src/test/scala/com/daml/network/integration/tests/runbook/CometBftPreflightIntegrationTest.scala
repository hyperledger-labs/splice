package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import io.circe.*, io.circe.parser.*

import java.net.Socket

/** Preflight test that makes sure that the cometBFT nodes of *our* SVs (1-4) have initialized fine.
  */
class CometBftPreflightIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with PreflightIntegrationTestUtil {

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

  "Sv4 prunes its CometBFT stack" in { implicit env =>
    val RetainBlock = 10000 // check parameter configured in CometBft helm chart

    val auth0 = auth0UtilFromEnvVars("https://canton-network-dev.us.auth0.com")

    val token = eventuallySucceeds() {
      getAuth0ClientCredential(
        "CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN",
        "https://canton.network.global",
        auth0,
      )(noTracingLogger)
    }

    val sv4 = svcl("sv4").copy(token = Some(token))

    val status = parse(
      sv4
        .cometBftNodeDebugDump()
        .status
        .toString()
    ).getOrElse(Json.Null)

    val syncInfo = status.hcursor.downField("sync_info")

    val latestBlockHeight = syncInfo
      .get[Int]("latest_block_height")
      .value

    val earliestBlockHeight = syncInfo
      .get[Int]("earliest_block_height")
      .value

    if (latestBlockHeight > RetainBlock) {
      (latestBlockHeight - earliestBlockHeight) should be < (RetainBlock * 1.05).toInt
    } else {
      earliestBlockHeight shouldBe 1
    }

  }

}
