package org.lfdecentralizedtrust.splice.integration.plugins

import cats.implicits.catsSyntaxOptionId
import org.lfdecentralizedtrust.splice.config.{SpliceConfig, ConfigTransforms}
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.cometbft.{CometBftConnectionConfig, CometBftContainer}
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import monocle.Monocle.toAppliedFocusOps
import org.testcontainers.containers.Network

import scala.collection.concurrent.TrieMap

/** Creates a new CometBft deployment for all the configured nodes
  * The node config must be set for a container to be created
  * The containers for a given environment share a docker network
  * The CometBFT container belonging to SV1  is always started as it's the sv1 in the genesis.json file
  */
class CometBftNetworkPlugin(
    identifier: String,
    protected val loggerFactory: NamedLoggerFactory,
) extends EnvironmentSetupPlugin[EnvironmentImpl, SpliceTestConsoleEnvironment] {

  private val runningContainers = new TrieMap[CometBftConnectionConfig, CometBftContainer]()
  private val networks = new TrieMap[CometBftConnectionConfig, Network]()
  override def beforeEnvironmentCreated(config: SpliceConfig): SpliceConfig = {
    val network = Network.newNetwork()

    /** SV1 is the validator so it must always be started first
      */
    val sv1Node: CometBftConnectionConfig = startNewCometBftContainer("sv1", network)
    networks.put(sv1Node, network)
    ConfigTransforms.updateAllSvAppConfigs { (name, config) =>
      val container = if (name == "sv1") sv1Node else startNewCometBftContainer(name, network)
      config.focus(_.cometBftConfig).modify(_.map(_.focus(_.connectionUri).replace(container.uri)))
    }(config)

  }

  override def afterEnvironmentDestroyed(config: SpliceConfig): Unit = {
    val containerConnectionKeys = config.svApps.values
      .flatMap(svApp => svApp.cometBftConfig.map(_.connectionUri))
      .map(CometBftConnectionConfig.apply)
    containerConnectionKeys.foreach { connectionKey =>
      val container = runningContainers(connectionKey)
      container.shutdown()
    }
    containerConnectionKeys.foreach(networks.get(_).foreach(_.close()))
  }

  def startNewCometBftContainer(id: String, network: Network): CometBftConnectionConfig = {
    val container = new CometBftContainer(identifier, CometBftContainer.SvNetwork(id))
    container.initialize(network.some)
    logger.info(
      s"Started new CometBft container on IP ${container.getIp} and port ${container.getPort}"
    )(TraceContext.empty)
    val connectionConfig = CometBftConnectionConfig(
      s"http://${container.getIp}:${container.getPort}"
    )
    runningContainers.put(
      connectionConfig,
      container,
    )
    connectionConfig
  }

}
