package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.auth.PreflightAuthUtil
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import io.circe.*
import io.circe.parser.*

import java.net.{InetSocketAddress, Socket}
import scala.util.Using

/** Preflight test that makes sure that the cometBFT nodes of *our* SVs (1-4) have initialized fine.
  */
class CometBftPreflightIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with PreflightAuthUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  private val domainIndex = sys.env.getOrElse("CN_MIGRATION_DOMAIN_INDEX", "0")

  "p2p port for all CometBft nodes is accessible" in { env =>
    env.svs.remote.zipWithIndex.map { case (_, index) =>
      val cometBftP2pHost = sys.env("NETWORK_APPS_ADDRESS")
      val port = s"26$domainIndex${index + 1}6".toInt
      clue(s"Connection to $cometBftP2pHost with port $port") {
        // All we care about is the p2p port for CometBFT being accessible by other nodes
        // The socket connects in the constructor, therefore if no error is thrown during the initialization then a successful TCP connection is established
        Using.resource(new Socket()) { socket =>
          socket.connect(new InetSocketAddress(cometBftP2pHost, port), 1000)
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

  if (sys.env.getOrElse("ENABLE_COMETBFT_PRUNING", "false") == "true") {

    val RetainBlocks = sys.env("COMETBFT_RETAIN_BLOCKS").toInt

    "SVs prune their CometBFT stack" in { implicit env =>
      Seq("sv1", "sv2", "sv3", "sv4").foreach(svName => {

        val sv = svclWithToken(svName)

        eventuallySucceeds() {
          val status = parse(
            sv
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

          if (latestBlockHeight > RetainBlocks) {
            (latestBlockHeight - earliestBlockHeight) should be < (RetainBlocks * 1.05).toInt
          } else {
            earliestBlockHeight shouldBe 1
          }
        }

      })
    }
  }

}
