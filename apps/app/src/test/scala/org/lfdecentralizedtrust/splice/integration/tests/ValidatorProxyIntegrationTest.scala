package org.lfdecentralizedtrust.splice.integration.tests

import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}
import org.lfdecentralizedtrust.splice.unit.http.{
  SystemProperties,
  SystemPropertiesSupport,
  TinyProxySupport,
}

class ValidatorProxyIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with ProcessTestUtil
    with StandaloneCanton
    with TinyProxySupport
    with SystemPropertiesSupport {

  override def dbsSuffix = "validator_proxy"
  val dbName = s"participant_alice_validator_${dbsSuffix}"
  override def usesDbs = Seq(dbName) ++ super.usesDbs

  // Can sometimes be unhappy when doing funky `withCanton` things; disabling them for simplicity
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition.simpleTopology1SvWithLocalValidator(this.getClass.getSimpleName)
  }

  "validator should start and tap, using http forward proxy" in { implicit env =>
    val host = "127.0.0.1"
    initDsoWithSv1Only()
    withProxy() { proxy =>
      val props =
        SystemProperties()
          .set("https.proxyHost", "localhost")
          .set("https.proxyPort", proxy.port.toString)
          // localhost and tcp loopback addresses are not proxied by default in gRPC and Java URL connections
          // so we need to override the nonProxyHosts to ensure our proxy is used for all connections
          .set("http.nonProxyHosts", "")
      withProperties(props) {
        withCanton(
          Seq(
            testResourcesPath / "standalone-participant-extra.conf"
          ),
          Seq.empty,
          "validator-proxy-test",
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> dbName,
          "JAVA_TOOL_OPTIONS" -> props.toJvmOptionString,
        ) {
          aliceValidatorLocalBackend.startSync()
          actAndCheck(
            "Onboard wallet user",
            onboardWalletUser(aliceWalletClient, aliceValidatorLocalBackend),
          )(
            "We can tap and list",
            _ => {
              aliceWalletClient.tap(1)
              aliceWalletClient.list()
            },
          )
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
            aliceValidatorLocalBackend.config.clientAdminApi.port.unwrap,
          ) shouldBe true

          proxy.proxiedConnectRequest(
            host,
            aliceValidatorLocalBackend.participantClient.config.ledgerApi.port.unwrap,
          ) shouldBe true

          val sequencerPort = Uri(
            sv1Backend.config.localSynchronizerNodes.current.sequencer.externalPublicApiUrl
          ).effectivePort

          eventuallySucceeds() {
            proxy.proxiedConnectRequest(
              "localhost", // the sequencer in standalone canton is on localhost, not loopback
              sequencerPort,
            ) shouldBe true
          }

          proxy.hasNoErrors shouldBe true
        }
      }
    }
  }

  "validator should bypass the proxy for hosts matched by http.nonProxyHosts" in { implicit env =>
    // Note: this test does NOT assert anything about the sequencer or the
    // participant ledger API. Those are gRPC, not HTTP, and are routed by
    // Canton's gRPC channel — completely outside the scope of our
    // `HttpClient` `nonProxyHosts` change.
    val host = "127.0.0.1"
    initDsoWithSv1Only()
    withProxy() { proxy =>
      val scanPort = sv1ScanBackend.config.clientAdminApi.port.unwrap
      val sv1Port = sv1Backend.config.clientAdminApi.port.unwrap
      val walletUiPort = aliceWalletClient.config.clientAdminApi.url.effectivePort
      val validatorAdminPort = aliceValidatorLocalBackend.config.clientAdminApi.port.unwrap
      val props =
        SystemProperties()
          .set("https.proxyHost", "localhost")
          .set("https.proxyPort", proxy.port.toString)
          // Bypass the proxy for 127.0.0.1 (where all CN-app HTTP endpoints
          // in this topology live).
          .set("http.nonProxyHosts", "127.0.0.1")
      withProperties(props) {
        withCanton(
          Seq(
            testResourcesPath / "standalone-participant-extra.conf"
          ),
          Seq.empty,
          "validator-proxy-nonproxy-hosts-test",
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> dbName,
          "JAVA_TOOL_OPTIONS" -> props.toJvmOptionString,
        ) {
          aliceValidatorLocalBackend.startSync()
          actAndCheck(
            "Onboard wallet user",
            onboardWalletUser(aliceWalletClient, aliceValidatorLocalBackend),
          )(
            "We can tap and list",
            _ => {
              aliceWalletClient.tap(1)
              aliceWalletClient.list()
            },
          )
          // All CN-app HTTP targets on 127.0.0.1 must NOT have been proxied
          proxy.proxiedConnectRequest(host, scanPort) shouldBe false
          proxy.proxiedConnectRequest(host, sv1Port) shouldBe false
          proxy.proxiedConnectRequest(host, walletUiPort) shouldBe false
          proxy.proxiedConnectRequest(host, validatorAdminPort) shouldBe false

          proxy.hasNoErrors shouldBe true
        }
      }
    }
  }
}
