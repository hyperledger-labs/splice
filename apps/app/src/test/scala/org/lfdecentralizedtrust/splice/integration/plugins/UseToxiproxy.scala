package org.lfdecentralizedtrust.splice.integration.plugins.toxiproxy

import org.lfdecentralizedtrust.splice.config.{SpliceConfig, ParticipantClientConfig}
import org.lfdecentralizedtrust.splice.sv.config.SvParticipantClientConfig
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import eu.rekawek.toxiproxy.{Proxy, ToxiproxyClient}
import monocle.macros.syntax.lens.*
import org.apache.pekko.http.scaladsl.model.Uri

import scala.collection.mutable.Map

/** A test plugin which injects toxiproxy to certain connections, a much-simplified version of the equivalent plugin in Canton.
  * At the moment, we support only the SV apps' ledger api connections and the scan app's HTTP connections, but as we need to add more - we will generalize the code below.
  */
case class UseToxiproxy(
    // these arguments are just a way to reduce startup time without investing into a proper generalization yet.
    createSvLedgerApiProxies: Boolean = false,
    createScanAppProxies: Boolean = false,
    createScanLedgerApiProxy: Boolean = false,
) extends EnvironmentSetupPlugin[EnvironmentImpl, SpliceTestConsoleEnvironment]
    with BaseTest {

  val client = new ToxiproxyClient()
  val proxies = Map[String, Proxy]()

  // based on apps/app/src/test/resources/README.md
  val portBump = 20000

  private def addProxy(name: String, listenAddress: String, upstreamAddress: String) = {
    logger.info(s"Setting up proxy: ${name}: ${listenAddress} -> ${upstreamAddress}")
    val proxy = client.createProxy(name, listenAddress, upstreamAddress)
    proxies += (name -> proxy)
  }

  def addLedgerApiProxy(
      instanceName: String,
      participantClient: SvParticipantClientConfig,
      extraPortBump: Int,
  ): SvParticipantClientConfig = {
    val bump = portBump + extraPortBump
    val lapiHost = participantClient.ledgerApi.clientConfig.address
    val lapiPort = participantClient.ledgerApi.clientConfig.port
    val upstream = s"${lapiHost}:${lapiPort}"
    val listenPort = lapiPort + bump
    addProxy(s"${instanceName}-ledger-api", s"localhost:${listenPort}", upstream)
    participantClient.focus(_.ledgerApi.clientConfig).modify(c => c.copy(port = c.port + bump))
  }

  def addLedgerApiProxy(
      instanceName: String,
      participantClient: ParticipantClientConfig,
      extraPortBump: Int,
  ): ParticipantClientConfig = {
    val bump = portBump + extraPortBump
    val lapiHost = participantClient.ledgerApi.clientConfig.address
    val lapiPort = participantClient.ledgerApi.clientConfig.port
    val upstream = s"${lapiHost}:${lapiPort}"
    val listenPort = lapiPort + bump
    addProxy(s"${instanceName}-ledger-api", s"localhost:${listenPort}", upstream)
    participantClient.focus(_.ledgerApi.clientConfig).modify(c => c.copy(port = c.port + bump))
  }

  override def beforeEnvironmentCreated(config: SpliceConfig): SpliceConfig = {

    def addScanAppHttpProxy(
        instanceName: String,
        remoteScanApp: Uri,
        extraPortBump: Int,
    ): Uri = {
      val bump = portBump + extraPortBump

      val originalPort = remoteScanApp.effectivePort

      val listenPort = originalPort + bump
      val listen = s"localhost:$listenPort"

      // need to remove http-prefix for toxiproxy
      val upstream = s"localhost:$originalPort"

      addProxy(s"${instanceName}-scan-api", listen, upstream)
      remoteScanApp.withPort(listenPort)
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
              .map { case ((n, config), i) =>
                val basePortBump = i * 100
                config.scanClient match {
                  case BftScanClientConfig.TrustSingle(url, amuletRulesCacheTimeToLive) =>
                    val newUrl = addScanAppHttpProxy(n.unwrap, url, basePortBump)
                    (
                      n,
                      config.copy(scanClient =
                        BftScanClientConfig.TrustSingle(newUrl, amuletRulesCacheTimeToLive)
                      ),
                    )
                  case BftScanClientConfig.Bft(seedUrls, _, amuletRulesCacheTimeToLive) =>
                    val newUrl = addScanAppHttpProxy(n.unwrap, seedUrls.head, basePortBump)
                    (
                      n,
                      config.copy(scanClient =
                        BftScanClientConfig.TrustSingle(newUrl, amuletRulesCacheTimeToLive)
                      ),
                    )
                }
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

  override def afterEnvironmentDestroyed(config: SpliceConfig): Unit = {
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
