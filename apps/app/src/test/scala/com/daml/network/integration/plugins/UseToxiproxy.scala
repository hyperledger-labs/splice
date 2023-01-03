package com.daml.network.integration.plugins.toxiproxy

import com.daml.network.config.CoinConfig
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.svc.config.SvcAppBackendConfig
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import eu.rekawek.toxiproxy.{Proxy, ToxiproxyClient}
import monocle.macros.syntax.lens.*

import scala.collection.mutable.Map

/** A test plugin which injects toxiproxy to certain connections, a much-simplified version of the equivalent plugin in Canton.
  * At the moment, we support only the svc's ledger api connection, but as we need to add more - we will generalize the code below.
  */
case class UseToxiproxy()
    extends EnvironmentSetupPlugin[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
    with BaseTest {

  val client = new ToxiproxyClient()
  val proxies = Map[String, Proxy]()

  override def beforeEnvironmentCreated(config: CoinConfig): CoinConfig = {
    val transformSvc = (svcOption: Option[SvcAppBackendConfig]) => {
      svcOption.map(svc => {
        val lapiHost = svc.remoteParticipant.ledgerApi.clientConfig.address
        val lapiPort = svc.remoteParticipant.ledgerApi.clientConfig.port
        val upstream = s"${lapiHost}:${lapiPort}"
        val listenPort = lapiPort + 20000
        val listen = s"localhost:${listenPort}"
        val name = "svc-ledger-api"
        val proxy = client.createProxy(name, listen, upstream)
        proxies += (name -> proxy)
        svc
          .focus(_.remoteParticipant.ledgerApi.clientConfig)
          .modify(c => c.copy(port = c.port + 20000))
      })
    }
    config.focus(_.svcApp).modify(svc => transformSvc(svc))
  }

  override def afterEnvironmentDestroyed(config: CoinConfig): Unit = {
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
