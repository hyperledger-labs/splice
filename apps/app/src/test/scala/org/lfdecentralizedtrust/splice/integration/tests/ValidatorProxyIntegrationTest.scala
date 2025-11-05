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

  "validator should start and tap, using http forward proxy" in { implicit env =>
    val proxyUser = "user"
    val proxyPassword = "pass1"
    val localhost = "127.0.0.1"
    withProxy(auth = Some((proxyUser, proxyPassword))) { proxy =>
      val props =
        SystemProperties()
          .set("https.proxyHost", "localhost")
          .set("https.proxyPort", proxy.port.toString)
          .set("https.proxyUser", proxyUser)
          .set("https.proxyPassword", proxyPassword)
      withProperties(props) {
        initDsoWithSv1Only()
        aliceValidatorBackend.startSync()
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser).discard
        aliceWalletClient.tap(10)
        // the proxy should have seen connections to scan, sv1, alice's wallet and validator (at least once each)
        proxy.proxiedConnectRequest(
          localhost,
          sv1ScanBackend.config.clientAdminApi.port.unwrap,
        ) shouldBe true
        proxy.proxiedConnectRequest(
          localhost,
          sv1Backend.config.clientAdminApi.port.unwrap,
        ) shouldBe true
        proxy.proxiedConnectRequest(
          localhost,
          aliceWalletClient.config.clientAdminApi.url.effectivePort,
        ) shouldBe true
        proxy.proxiedConnectRequest(
          localhost,
          aliceValidatorBackend.config.clientAdminApi.port.unwrap,
        ) shouldBe true
        proxy.hasNoErrors shouldBe true
      }
    }
  }
}
