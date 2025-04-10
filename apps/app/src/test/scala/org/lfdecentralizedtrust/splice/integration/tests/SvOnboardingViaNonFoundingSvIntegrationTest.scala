package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_OffboardSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.bumpUrl
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, NetworkAppClientConfig}
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.SvAppClientConfig
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.JoinWithKey
import org.lfdecentralizedtrust.splice.util.{StandaloneCanton, SvTestUtil}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.time.{Minute, Span}

class SvOnboardingViaNonFoundingSvIntegrationTest
    extends IntegrationTest
    with SvTestUtil
    with StandaloneCanton {

  override def dbsSuffix: String = "non_sv1_svs"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.withPausedSvOffboardingMediatorAndPartyToParticipantTriggers()(
          config
        )
      )
      .withPreSetup(_ => ())
      .withOnboardingParticipantPromotionDelayEnabled() // Test onboarding with participant promotion delay
      .addConfigTransformsToFront(
        (_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => ConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
      )
      .addConfigTransforms((_, configuration) => {
        val sv1ToSv2Bump = 100
        val sv2OnboardingSvClientUrl =
          configuration
            .svApps(InstanceName.tryCreate("sv2"))
            .onboarding
            .getOrElse(
              throw new IllegalStateException("Onboarding configuration not found.")
            ) match {
            case node: JoinWithKey => bumpUrl(sv1ToSv2Bump, node.svClient.adminApi.url.toString())
            case _ => throw new IllegalStateException("JoinWithKey configuration not found.")
          }
        val sv2BootstrapSequencerUrl =
          configuration
            .svApps(InstanceName.tryCreate("sv2"))
            .domains
            .global
            .url
            .map(bumpUrl(sv1ToSv2Bump, _))
        ConfigTransforms.updateAllSvAppConfigs { (name, config) =>
          if (name == "sv3") {
            config.copy(
              onboarding = config.onboarding
                .getOrElse(
                  throw new IllegalStateException("Onboarding configuration not found.")
                ) match {
                case node: JoinWithKey =>
                  Some(
                    JoinWithKey(
                      node.name,
                      SvAppClientConfig(NetworkAppClientConfig(sv2OnboardingSvClientUrl)),
                      node.publicKey,
                      node.privateKey,
                    )
                  )
                case _ => throw new IllegalStateException("JoinWithKey configuration not found.")
              },
              domains = config.domains.copy(global =
                config.domains.global
                  .copy(url = sv2BootstrapSequencerUrl)
              ),
            )
          } else config
        }(configuration)
      })
      .withManualStart

  "A new SV can: 1) onboard via a non-sv1 while sv1 is offboarded from the DSO and " +
    "2) bootstrap using a sequencer that is not sv1's sequencer" in { implicit env =>
      withCantonSvNodes(
        (
          Some(sv1Backend),
          Some(sv2Backend),
          Some(sv3Backend),
          None,
        ),
        logSuffix = "sv123-non-sv1-svs",
        sv4 = false,
      )() {
        actAndCheck(
          "Initialize DSO with SV1 and SV2",
          startAllSync(
            sv1ScanBackend,
            sv2ScanBackend,
            sv1Backend,
            sv2Backend,
          ),
        )(
          "SV1 and SV2 are part of the DSO",
          _ => {
            sv2Backend
              .getDsoInfo()
              .dsoRules
              .payload
              .svs
              .keySet() should contain theSameElementsAs Seq(sv1Backend, sv2Backend).map(
              _.getDsoInfo().svParty.toProtoPrimitive
            )
          },
        )
        actAndCheck(
          "SV2 creates a request to offboard SV1",
          sv2Backend.createVoteRequest(
            sv2Backend.getDsoInfo().svParty.toProtoPrimitive,
            new ARC_DsoRules(
              new SRARC_OffboardSv(
                new DsoRules_OffboardSv(sv1Backend.getDsoInfo().svParty.toProtoPrimitive)
              )
            ),
            "url",
            "description",
            sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
            None,
          ),
        )("the request is created", _ => sv1Backend.listVoteRequests() should not be empty)
        actAndCheck(
          "SV1 accepts the request as it requires two votes",
          sv1Backend.castVote(
            sv1Backend.getLatestVoteRequestTrackingCid(),
            isAccepted = true,
            "url",
            "description",
          ),
        )(
          "SV2 is now the only sv",
          _ => {
            sv2Backend
              .getDsoInfo()
              .dsoRules
              .payload
              .svs
              .keySet() should contain theSameElementsAs Seq(
              sv2Backend
            ).map(_.getDsoInfo().svParty.toProtoPrimitive)
          },
        )
        clue("SV1 is offboarded from the Decentralized Namespace") {
          eventually() {
            val decentralizedNamespaces =
              sv2Backend.participantClient.topology.decentralized_namespaces
                .list(
                  filterStore = decentralizedSynchronizerId.filterString,
                  filterNamespace = dsoParty.uid.namespace.toProtoPrimitive,
                )
            inside(decentralizedNamespaces) { case Seq(decentralizedNamespace) =>
              decentralizedNamespace.item.owners shouldBe Seq(
                sv2Backend
              )
                .map(_.participantClient.id.uid.namespace)
                .toSet
            }
          }
        }
        clue("Stop SV1") {
          sv1Backend.stop()
        }
        actAndCheck(
          "Onboard SV3",
          sv3Backend.startSync(),
        )(
          "SV3 is now sv",
          _ => {
            sv2Backend
              .getDsoInfo()
              .dsoRules
              .payload
              .svs
              .keySet() should contain theSameElementsAs Seq(sv2Backend, sv3Backend).map(
              _.getDsoInfo().svParty.toProtoPrimitive
            )
          },
        )
      }
    }
}
