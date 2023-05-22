package com.daml.network.sv.cometbft

import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.testcontainers.containers.Network

import scala.collection.concurrent.TrieMap

trait SvCometBftNetwork extends BeforeAndAfterAll with NamedLogging {
  this: Suite =>

  private val runningContainers = new TrieMap[String, CometBftContainer]()
  private val network = Network.newNetwork()

  /** SV1 is the validator so it must always be started first
    */
  val sv1Node: CometBftConnectionConfig = startNewCometBftContainer("sv1")

  def startNewCometBftContainer(id: String): CometBftConnectionConfig = {
    val container = runningContainers.getOrElseUpdate(
      id, {
        val container = new CometBftContainer(CometBftContainer.SvNetwork(id))
        container.initialize(network.some)
        logger.info(
          s"Started new CometBft container on IP ${container.getIp} and port ${container.getPort}"
        )(TraceContext.empty)
        container
      },
    )
    CometBftConnectionConfig(
      s"http://${container.getIp}:${container.getPort}"
    )
  }

  override def afterAll(): Unit = {
    super.afterAll()
    runningContainers.values.foreach(_.shutdown())
  }

}
