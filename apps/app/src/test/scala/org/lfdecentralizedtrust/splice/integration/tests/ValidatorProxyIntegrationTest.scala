package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.discard.Implicits.DiscardOps
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import org.lfdecentralizedtrust.splice.unit.http.{
  SystemProperties,
  SystemPropertiesSupport,
  TinyProxySupport,
}

class ValidatorProxyIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TinyProxySupport
    with SystemPropertiesSupport {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart

  "validator should start and tap, using http forward proxy without proxy authentication" in {
    implicit env =>
      val host = "127.0.0.1"
      withProxy() { proxy =>
        val props =
          SystemProperties()
            .set("https.proxyHost", "localhost")
            .set("https.proxyPort", proxy.port.toString)
            // localhost and tcp loopback addresses are not proxied by default in gRPC and Java URL connections
            // so we need to override the nonProxyHosts to ensure our proxy is used for all connections
            .set("http.nonProxyHosts", "")
            .set("https.nonProxyHosts", "")
        withProperties(props) {
          initDsoWithSv1Only()
          aliceValidatorBackend.startSync()
          aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser).discard
          aliceWalletClient.tap(10)
          val aliceTxs = aliceWalletClient.listTransactions(None, 10)
          aliceTxs should not be empty
          // the proxy should have seen connections to scan, sv1, alice's wallet and validator, participant and the sequencer (at least once each)
          proxy.proxiedConnectRequest(
            host,
            sv1ScanBackend.config.clientAdminApi.port.unwrap,
          ) shouldBe true
          proxy.proxiedConnectRequest(
            host,
            sv1Backend.config.clientAdminApi.port.unwrap,
          ) shouldBe true
          proxy.proxiedConnectRequest(
            host,
            aliceWalletClient.config.clientAdminApi.url.effectivePort,
          ) shouldBe true
          proxy.proxiedConnectRequest(
            host,
            aliceValidatorBackend.config.clientAdminApi.port.unwrap,
          ) shouldBe true
          proxy.proxiedConnectRequest(
            host,
            sv1ScanBackend.config.participantClient.ledgerApi.clientConfig.port.unwrap,
          ) shouldBe true
          proxy.proxiedConnectRequest(
            host,
            sv1Backend.sequencerClient.config.clientAdminApi.port.unwrap,
          ) shouldBe true

          proxy.hasNoErrors shouldBe true
        }
      }
  }
}
