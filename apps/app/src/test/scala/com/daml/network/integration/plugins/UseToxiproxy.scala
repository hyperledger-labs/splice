package com.daml.network.integration.plugins.toxiproxy

import com.daml.network.config.{CNNodeConfig, CNParticipantClientConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.scan.config.ScanAppClientConfig
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import eu.rekawek.toxiproxy.{Proxy, ToxiproxyClient}
import monocle.macros.syntax.lens.*

import scala.collection.mutable.Map

/** A test plugin which injects toxiproxy to certain connections, a much-simplified version of the equivalent plugin in Canton.
  * At the moment, we support only the SV apps' ledger api connections and the scan app's HTTP connections, but as we need to add more - we will generalize the code below.
  */
case class UseToxiproxy(
    // these arguments are just a way to reduce startup time without investing into a proper generalization yet.
    createSvLedgerApiProxies: Boolean = false,
    createScanAppProxies: Boolean = false,
    createScanLedgerApiProxy: Boolean = false,
) extends EnvironmentSetupPlugin[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment]
    with BaseTest {

  val client = new ToxiproxyClient()
  val proxies = Map[String, Proxy]()

  private def addProxy(name: String, listenAddress: String, upstreamAddress: String) = {
    logger.info(s"Setting up proxy: ${name}: ${listenAddress} -> ${upstreamAddress}")
    val proxy = client.createProxy(name, listenAddress, upstreamAddress)
    proxies += (name -> proxy)
  }

  override def beforeEnvironmentCreated(config: CNNodeConfig): CNNodeConfig = {
    def addLedgerApiProxy(
        instanceName: String,
        participantClient: CNParticipantClientConfig,
        extraPortBump: Int,
    ): CNParticipantClientConfig = {
      val bump = 20000 + extraPortBump
      val lapiHost = participantClient.ledgerApi.clientConfig.address
      val lapiPort = participantClient.ledgerApi.clientConfig.port
      val upstream = s"${lapiHost}:${lapiPort}"
      val listenPort = lapiPort + bump
      addProxy(s"${instanceName}-ledger-api", s"localhost:${listenPort}", upstream)
      participantClient.focus(_.ledgerApi.clientConfig).modify(c => c.copy(port = c.port + bump))
    }

    def addScanAppHttpProxy(
        instanceName: String,
        remoteScanApp: ScanAppClientConfig,
        extraPortBump: Int,
    ): ScanAppClientConfig = {
      val bump = 20000 + extraPortBump

      val originalPort = remoteScanApp.adminApi.url.effectivePort

      val listenPort = originalPort + bump
      val listen = s"localhost:$listenPort"

      // need to remove http-prefix for toxiproxy
      val upstream = s"localhost:$originalPort"

      addProxy(s"${instanceName}-scan-api", listen, upstream)
      remoteScanApp.focus(_.adminApi.url).modify(_.withPort(listenPort))
    }

    val svLedgerApiConf =
      if (createSvLedgerApiProxies)
        config
          .focus(_.svApps)
          .modify(
            _.toSeq
              .sortBy(_._1.unwrap) // sv1, sv2, ...
              .zipWithIndex // for adapting the port bump
              .map { case ((n, c), i) =>
                (n, c.copy(participantClient = addLedgerApiProxy(n.unwrap, c.participantClient, i)))
              }
              .toMap
          )
      else config

    val scanAppConf =
      if (createScanAppProxies)
        svLedgerApiConf
          .focus(_.validatorApps)
          .modify(
            _.toSeq
              .sortBy(_._1.unwrap)
              .zipWithIndex // for adapting the port bump
              .map { case ((n, c), i) =>
                (n, c.copy(scanClient = addScanAppHttpProxy(n.unwrap, c.scanClient, i)))
              }
              .toMap
          )
      else svLedgerApiConf

    val scanLedgerApiConf =
      if (createScanLedgerApiProxy)
        scanAppConf
          .focus(_.scanApps)
          .modify(
            _.toSeq
              .sortBy(_._1.unwrap)
              .zipWithIndex // for adapting the port bump
              .map { case ((n, c), i) =>
                (n, c.copy(participantClient = addLedgerApiProxy(n.unwrap, c.participantClient, i)))
              }
              .toMap
          )
      else scanAppConf

    scanLedgerApiConf
  }

  override def afterEnvironmentDestroyed(config: CNNodeConfig): Unit = {
    logger.debug("deleting all proxies. ")
    proxies.foreach { case (_, p) => p.delete() }
  }

  def disableConnectionViaProxy(connection: String): Unit = {
    proxies.get(connection) match {
      case Some(p) =>
        logger.info(s"Disabled $connection")
        p.disable()
      case _ => fail(s"No proxy named ${connection}")
    }
  }

  def enableConnectionViaProxy(connection: String): Unit = {
    proxies.get(connection) match {
      case Some(p) =>
        logger.info(s"Enabled connection $connection")
        p.enable()
      case _ => fail(s"No proxy named ${connection}")
    }
  }
}

object UseToxiproxy {
  def ledgerApiProxyName(forInstance: String): String = s"$forInstance-ledger-api"
  def scanHttpApiProxyName(forInstance: String): String = s"$forInstance-scan-api"
}
