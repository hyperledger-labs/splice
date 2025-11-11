package org.lfdecentralizedtrust.splice.integration.tests

import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton, WalletTestUtil}
import org.lfdecentralizedtrust.splice.unit.http.{
  SystemProperties,
  SystemPropertiesSupport,
  TinyProxySupport,
}


class ValidatorProxyIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with ProcessTestUtil
    with StandaloneCanton
    with TinyProxySupport
    with SystemPropertiesSupport {
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def dbsSuffix = "validator_proxy"
  val dbName = s"participant_alice_validator_${dbsSuffix}"
  override def usesDbs = Seq(dbName) ++ super.usesDbs

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) =>
        ConfigTransforms.bumpSomeValidatorAppCantonPortsBy(22000, Seq("aliceValidator"))(conf)
      )
      // Splice apps should only start after the Canton instances are started
      .withManualStart
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
          .set("https.nonProxyHosts", "")
      withProperties(props) {
        withCanton(
          Seq(testResourcesPath / "standalone-participant-extra.conf"),
          Seq.empty,
          "validator-proxy-test",
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> dbName,
          "JAVA_TOOL_OPTIONS" -> props.toJvmOptionString,
        ) {
          aliceValidatorBackend.startSync()
          actAndCheck(
            "Onboard wallet user",
            onboardWalletUser(aliceWalletClient, aliceValidatorBackend),
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
            aliceValidatorBackend.config.clientAdminApi.port.unwrap,
          ) shouldBe true

          proxy.proxiedConnectRequest(
            host,
            aliceValidatorBackend.participantClient.config.ledgerApi.port.unwrap,
          ) shouldBe true

          val sequencerPort = Uri(
            sv1Backend.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
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
}
