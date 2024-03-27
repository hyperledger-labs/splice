package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.dsorules.DsoRules_OffboardSv
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import com.daml.network.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv
import com.daml.network.config.CNNodeConfigTransforms.bumpUrl
import com.daml.network.config.{CNNodeConfigTransforms, NetworkAppClientConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.SvAppClientConfig
import com.daml.network.sv.config.SvOnboardingConfig.JoinWithKey
import com.daml.network.util.{StandaloneCanton, SvTestUtil}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.time.{Minute, Span}

class SvOnboardingViaNonFoundingSvIntegrationTest
    extends CNNodeIntegrationTest
    with SvTestUtil
    with StandaloneCanton {

  override def dbsSuffix: String = "non_founding_svs"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .withOnboardingParticipantPromotionDelayEnabled() // Test onboarding with participant promotion delay
      .addConfigTransformsToFront(
        (_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => CNNodeConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
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
          bumpUrl(
            sv1ToSv2Bump,
            configuration
              .svApps(InstanceName.tryCreate("sv2"))
              .domains
              .global
              .url,
          )
        CNNodeConfigTransforms.updateAllSvAppConfigs { (name, config) =>
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

  "A new SV can: 1) onboard via a non-founding SV while founding SV is offboarded from the DSO and " +
    "2) bootstrap using a sequencer that is not founding SV's sequencer" in { implicit env =>
      withCantonSvNodes(
        (
          Some(sv1Backend),
          Some(sv2Backend),
          Some(sv3Backend),
          None,
        ),
        logSuffix = "sv123-non-founding-svs",
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
          "SV2 is now the only member",
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
                  filterStore = globalDomainId.filterString,
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
          "SV3 is now member",
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
