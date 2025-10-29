package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.{ParticipantClientReference, SvAppBackendReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.sequencing.GrpcSequencerConnection

import scala.concurrent.duration.*

class SequencerNameFilteringIntegrationTest extends IntegrationTest with SvTestUtil with WalletTestUtil {

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
                  sequencerNames = Some(Seq(getSvName(1), getSvName(2), getSvName(3)))
                )
              ),
              automation = c.automation.copy(pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)),
            )
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "validator with 'sequencerNames' config connects only to specified sequencers" in { implicit env =>
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

    withClue("Validator should connect to the filtered list of sequencers from the config") {
      eventually(30.seconds, 1.second) {
        val connectedUrls = getSequencerPublicUrls(
          aliceValidatorBackend.participantClientWithAdminToken,
          globalSyncAlias,
        )

        withClue("Should be connected to SV1's sequencer:") {
          connectedUrls should contain(getPublicSequencerUrl(sv1Backend))
        }
        withClue("Should be connected to SV2's sequencer:") {
          connectedUrls should contain(getPublicSequencerUrl(sv2Backend))
        }

        withClue("Should be connected to SV3's sequencer:") {
          connectedUrls should contain(getPublicSequencerUrl(sv3Backend))
        }

        withClue("Should NOT be connected to SV4's sequencer:") {
          connectedUrls should not contain (getPublicSequencerUrl(sv4Backend))
        }
      }
    }

    withClue("Alice's validator should be functional and able to onboard a user") {
      eventuallySucceeds() {
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      }
    }
  }


  private def getPublicSequencerUrl(sv: SvAppBackendReference): String =
    sv.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl

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
