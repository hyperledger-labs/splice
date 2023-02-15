package com.daml.network.integration.plugins.toxiproxy

import com.daml.network.config.{CNNodeConfig, CoinRemoteParticipantConfig}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import eu.rekawek.toxiproxy.{Proxy, ToxiproxyClient}
import monocle.macros.syntax.lens.*

import scala.collection.mutable.Map

/** A test plugin which injects toxiproxy to certain connections, a much-simplified version of the equivalent plugin in Canton.
  * At the moment, we support only the svs's ledger api connections, but as we need to add more - we will generalize the code below.
  */
case class UseToxiproxy()
    extends EnvironmentSetupPlugin[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
    with BaseTest {

  val client = new ToxiproxyClient()
  val proxies = Map[String, Proxy]()

  override def beforeEnvironmentCreated(config: CNNodeConfig): CNNodeConfig = {
    def addLedgerApiProxy(
        instanceName: String,
        remoteParticipant: CoinRemoteParticipantConfig,
        extraPortBump: Int,
    ): CoinRemoteParticipantConfig = {
      val bump = 20000 + extraPortBump
      val lapiHost = remoteParticipant.ledgerApi.clientConfig.address
      val lapiPort = remoteParticipant.ledgerApi.clientConfig.port
      val upstream = s"${lapiHost}:${lapiPort}"
      val listenPort = lapiPort + bump
      val listen = s"localhost:${listenPort}"
      val name = s"${instanceName}-ledger-api"
      logger.info(s"Setting up proxy: ${name}: ${listen} -> ${upstream}")
      val proxy = client.createProxy(name, listen, upstream)
      proxies += (name -> proxy)
      remoteParticipant.focus(_.ledgerApi.clientConfig).modify(c => c.copy(port = c.port + bump))
    }
    config
      .focus(_.svApps)
      .modify(
        _.toSeq
          .sortBy(_._1.unwrap) // sv1, sv2, ...
          .zipWithIndex // for adapting the port bump
          .map { case ((n, c), i) =>
            (n, c.copy(remoteParticipant = addLedgerApiProxy(n.unwrap, c.remoteParticipant, i)))
          }
          .toMap
      )
  }

  override def afterEnvironmentDestroyed(config: CNNodeConfig): Unit = {
    proxies.foreach { case (_, p) => p.delete() }
  }

  def disable(connection: String): Unit = {
    proxies.get(connection) match {
      case Some(p) => p.disable()
      case _ => fail(s"No proxy named ${connection}")
    }
  }

  def enable(connection: String): Unit = {
    proxies.get(connection) match {
      case Some(p) => p.enable()
      case _ => fail(s"No proxy named ${connection}")
    }
  }
}
