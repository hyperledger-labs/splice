package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.GrpcSequencerConnection
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_OffboardSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  bumpUrl,
  IsTheCantonSequencerBFTEnabled,
}
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, NetworkAppClientConfig}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.{LocalSynchronizerNode, SvAppClientConfig}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvBftSequencerPeerOffboardingTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding.SvOffboardingSequencerTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.JoinWithKey
import org.lfdecentralizedtrust.splice.util.{StandaloneCanton, SvTestUtil}
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.time.{Minute, Span}

class SvOnboardingViaNonFoundingSvIntegrationTest
    extends IntegrationTest
    with SvTestUtil
    with StandaloneCanton {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def dbsSuffix: String = "non_sv1_svs"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .withOnboardingParticipantPromotionDelayEnabled() // Test onboarding with participant promotion delay
      .addConfigTransformsToFront((_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf))
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
            // We need the validator backend for sequencer url reconciliation
            sv2ValidatorBackend,
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
        clue(
          "sv2 uses its own sequencer to handle offboarding sv1 and is connected to the global synchronizer"
        ) {
          eventually(timeUntilSuccess = 2.minutes) {
            val endpoints = sv2Backend.participantClient.synchronizers
              .config(decentralizedSynchronizerAlias)
              .value
              .sequencerConnections
              .connections
              .forgetNE
              .map {
                inside(_) { case GrpcSequencerConnection(endpoints, _, _, _, _) =>
                  endpoints.forgetNE.loneElement
                }
              }
            endpoints.toSet shouldBe Set(
              LocalSynchronizerNode.toEndpoint(
                sv1Backend.config.localSynchronizerNode.value.sequencer.internalApi
              ),
              LocalSynchronizerNode.toEndpoint(
                sv2Backend.config.localSynchronizerNode.value.sequencer.internalApi
              ),
            )
            sv2Backend.participantClient.synchronizers.is_connected(
              decentralizedSynchronizerAlias
            ) shouldBe true
          }
        }
        actAndCheck(
          "SV2 creates a request to offboard SV1",
          eventuallySucceeds() {
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
            )
          },
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
                  store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
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

        // resume only after the other offboarding triggeres finished to not brick sv1's sequencer too early
        if (IsTheCantonSequencerBFTEnabled) {
          sv1Backend.dsoAutomation.trigger[SvOffboardingSequencerTrigger].resume()
          sv2Backend.dsoAutomation.trigger[SvOffboardingSequencerTrigger].resume()
          eventually() {
            sv2Backend.participantClient.topology.sequencers
              .list(
                store = Some(TopologyStoreId.Synchronizer(decentralizedSynchronizerId))
              )
              .loneElement
              .item
              .allSequencers
              .toIndexedSeq should have size 1
            sv2Backend.appState.localSynchronizerNode.value.sequencerAdminConnection
              .getSequencerOrderingTopology()
              .futureValue
              .sequencerIds should have size 1
          }
          sv2Backend.dsoAutomation.trigger[SvBftSequencerPeerOffboardingTrigger].resume()
        }
        clue("Stop SV1") {
          sv1Backend.stop()
        }
        actAndCheck(
          "Onboard SV3",
          startAllSync(sv3Backend, sv3ScanBackend),
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
