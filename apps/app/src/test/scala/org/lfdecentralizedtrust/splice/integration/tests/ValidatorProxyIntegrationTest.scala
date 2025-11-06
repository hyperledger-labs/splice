package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.unit.http.{
  SystemProperties,
  SystemPropertiesSupport,
  TinyProxySupport,
}
import scala.concurrent.duration.*

class ValidatorProxyIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with ProcessTestUtil
    with TinyProxySupport
    with SystemPropertiesSupport {

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
        withCanton(cantonArgs, cantonExtraConfig, "validator-proxy-test") {
          aliceValidatorBackend.start()
          clue("Wait for validator initialization") {
            // Need to wait for the participant node to startup for the user allocation to go through
            eventuallySucceeds(timeUntilSuccess = 120.seconds) {
              EnvironmentDefinition.withAllocatedValidatorUser(aliceValidatorBackend)
            }
            aliceValidatorBackend.waitForInitialization()
          }
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
          println(
            "participant ledger port:" + aliceValidatorBackend.participantClient.config.ledgerApi.port.unwrap
          )
          proxy.proxiedConnectRequest(
            host,
            aliceValidatorBackend.participantClient.config.ledgerApi.port.unwrap,
          ) shouldBe true

          println(
            "external URL: " + sv1Backend.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
          )
          println("publicApi port:" + sv1Backend.sequencerClient.config.publicApi.port.unwrap)
          proxy.process.stdOutLines.foreach(println)
          proxy.proxiedConnectRequest(
            host,
            sv1Backend.sequencerClient.config.publicApi.port.unwrap,
          ) shouldBe true

          proxy.hasNoErrors shouldBe true
        }
      }
    }
  }

  val includeTestResourcesPath: File = testResourcesPath / "include"

  val cantonArgs: Seq[File] = Seq(
    includeTestResourcesPath / "validator-participant.conf",
    includeTestResourcesPath / "self-hosted-validator-disable-json-api.conf",
    includeTestResourcesPath / "self-hosted-validator-participant-postgres-storage.conf",
    includeTestResourcesPath / "storage-postgres.conf",
  )
  val cantonExtraConfig: Seq[String] =
    Seq(
      "canton.participants.validatorParticipant.ledger-api.port=7501",
      "canton.participants.validatorParticipant.admin-api.port=7502",
    )
  val ParticipantLedgerPort = 7501
  val ParticipantAdminPort = 7502
  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    ("ParticipantLedgerApi", ParticipantLedgerPort),
    ("ParticipantAdminApi", ParticipantAdminPort),
  )

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .addConfigTransforms((_, conf) =>
        ConfigTransforms.bumpSelfHostedParticipantPortsBy(2000)(conf)
      )
      // Do not allocate validator users here, as we deal with all of them manually
      .withAllocatedUsers(extraIgnoredValidatorPrefixes = Seq(""))
      .withManualStart
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()
  }
}
