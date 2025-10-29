package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.{ParticipantClientReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import org.apache.pekko.http.scaladsl.model.Uri

import scala.concurrent.duration.*

class SequencerNameFilteringIntegrationTest
    extends IntegrationTest
    with SvTestUtil
    with WalletTestUtil {

  private val globalSyncAlias = SynchronizerAlias.tryCreate("global")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            c.copy(
              domains = c.domains.copy(
                global = c.domains.global.copy(
                  sequencerNames = Some(Seq(getSvName(1), getSvName(2)))
                )
              ),
              automation =
                c.automation.copy(pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)),
            )
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "validator with 'sequencerNames' config connects only to specified sequencers" in {
    implicit env =>
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
        sv2Backend,
        sv2ScanBackend,
        sv3Backend,
        sv3ScanBackend,
        sv4Backend,
        sv4ScanBackend,
      )

      aliceValidatorBackend.startSync()

      val expectedUrls = Set(getPublicSequencerUrl(sv1Backend), getPublicSequencerUrl(sv2Backend))

      withClue("Validator should connect to the filtered list of sequencers from the config") {
        eventually(60.seconds, 1.second) {
          val connectedUrls = getSequencerPublicUrls(
            aliceValidatorBackend.participantClientWithAdminToken,
            globalSyncAlias,
          )
          connectedUrls shouldBe expectedUrls
        }
      }

      withClue("Alice's validator should be functional and able to onboard a user") {
        eventuallySucceeds() {
          aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
        }
      }
  }

  "validator connects to available sequencers when a configured one is down" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      sv3Backend,
      sv3ScanBackend,
      sv4Backend,
      sv4ScanBackend,
    )

    aliceValidatorBackend.startSync()

    withClue("Validator should connect only to the available sequencer from its configured list") {
      eventually(60.seconds, 1.second) {
        val connectedUrls = getSequencerPublicUrls(
          aliceValidatorBackend.participantClientWithAdminToken,
          globalSyncAlias,
        )
        connectedUrls shouldBe Set(getPublicSequencerUrl(sv1Backend))
      }
    }

    startAllSync(sv2Backend, sv2ScanBackend)

    withClue("Validator should dynamically connect to SV2 after it starts") {
      eventually(60.seconds, 1.second) {
        val connectedUrls = getSequencerPublicUrls(
          aliceValidatorBackend.participantClientWithAdminToken,
          globalSyncAlias,
        )
        connectedUrls shouldBe Set(
          getPublicSequencerUrl(sv1Backend),
          getPublicSequencerUrl(sv2Backend),
        )
      }
    }

    withClue("Alice's validator should remain functional") {
      eventuallySucceeds() {
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      }
    }
  }

  private def getPublicSequencerUrl(sv: SvAppBackendReference): String = {
    val fullUrl = sv.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
    Uri(fullUrl).authority.toString()
  }

  private def getSequencerPublicUrls(
      participantConnection: ParticipantClientReference,
      synchronizerAlias: SynchronizerAlias,
  ): Set[String] = {
    val sequencerConnections = participantConnection.synchronizers
      .config(synchronizerAlias)
      .value
      .sequencerConnections

    sequencerConnections.connections.forgetNE.collect {
      case GrpcSequencerConnection(endpoints, _, _, _, _) => endpoints.head1.toString
    }.toSet
  }
}
