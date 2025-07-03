package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.BftBlockOrdererConfig.P2PEndpointConfig
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

class SvDevNetReonboardingIntegrationTest extends SvIntegrationTestBase {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart
      .addConfigTransforms((_, conf) =>
        ConfigTransforms.updateAllSvAppConfigs((name, c) =>
          if (name == "sv4") {
            c.copy(
              onboarding = Some(conf.svApps(InstanceName.tryCreate("sv3")).onboarding.value)
            )
          } else {
            c
          }
        )(conf)
      )

  "Reonboarding an SV with the same name removes the old SV from PartyToParticipant and the Decentralized Namespace" in {
    implicit env =>
      clue("SV3 and SV4 use different participants") {
        sv3Backend.participantClient.id should not be sv4Backend.participantClient.id
      }
      clue("Initialize DSO with 3 SVs") {
        startAllSync(
          (sv1Nodes ++ sv2Nodes ++ sv3Nodes)*
        )
      }

      def checkPartyToParticipant(expected: Seq[ParticipantId]) = {
        eventually() {
          val mapping = sv1Backend.appState.participantAdminConnection
            .getPartyToParticipant(decentralizedSynchronizerId, sv1Backend.getDsoInfo().dsoParty)
            .futureValue
            .mapping
          mapping.threshold shouldBe PositiveInt.tryCreate(2)
          mapping.participants.map(_.participantId) should contain theSameElementsAs expected
        }
      }

      def checkDecentralizedNamespace(expected: Seq[SvAppBackendReference]) = {
        eventually() {
          val decentralizedNamespaces =
            sv1Backend.participantClient.topology.decentralized_namespaces
              .list(
                store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
                filterNamespace = dsoParty.uid.namespace.toProtoPrimitive,
              )
          inside(decentralizedNamespaces) { case Seq(decentralizedNamespace) =>
            decentralizedNamespace.item.owners shouldBe expected
              .map(_.participantClient.id.uid.namespace)
              .toSet
          }
        }
      }

      checkPartyToParticipant(
        Seq(
          sv1Backend.participantClient.id,
          sv2Backend.participantClient.id,
          sv3Backend.participantClient.id,
        )
      )
      checkDecentralizedNamespace(
        Seq(sv1Backend, sv2Backend, sv3Backend)
      )

      val sv3PartyId = eventually() {
        val svs = sv1Backend
          .getDsoInfo()
          .dsoRules
          .payload
          .svs
          .asScala
          .toMap

        svs should have size 3
        val sv3PartyId = sv3Backend.getDsoInfo().svParty
        inside(svs.get(sv3PartyId.toProtoPrimitive)) { case Some(svInfo) =>
          svInfo.name shouldBe getSvName(3)
        }
        sv3PartyId
      }

      sv3Backend.stop()
      sv3ScanBackend.stop()
      sv3ValidatorBackend.stop()

      actAndCheck(
        "start sv4 with sv3 onboarding config", {
          sv4Nodes.foreach(_.start())
          if (ConfigTransforms.IsTheCantonSequencerBFTEnabled) {
            // we don't offboard the old sv3 sequencer to not break the network for other tests so we just manually connect it
            eventuallySucceeds(5.minutes) {
              noException should be thrownBy {
                sv3Backend.sequencerClient.bft.add_peer_endpoint(
                  P2PEndpointConfig(
                    "localhost",
                    RequireTypes.Port.tryCreate(5410),
                    None,
                  )
                )
                sv4Backend.sequencerClient.bft.add_peer_endpoint(
                  P2PEndpointConfig(
                    "localhost",
                    RequireTypes.Port.tryCreate(5310),
                    None,
                  )
                )
              }
            }
          }
          sv4Nodes.foreach(_.waitForInitialization())
        },
      )(
        "old SV from PartyToParticipant is removed and sv3 is overwritten with different party id",
        _ => {
          checkPartyToParticipant(
            Seq(
              sv1Backend.participantClient.id,
              sv2Backend.participantClient.id,
              sv4Backend.participantClient.id,
            )
          )

          checkDecentralizedNamespace(Seq(sv1Backend, sv2Backend, sv4Backend))

          val newSv3PartyId = sv4Backend.getDsoInfo().svParty
          val newSvs = sv1Backend
            .getDsoInfo()
            .dsoRules
            .payload
            .svs
            .asScala
            .toMap

          newSvs should have size 3
          newSvs.get(sv3PartyId.toProtoPrimitive) shouldBe empty

          inside(
            newSvs
              .get(newSv3PartyId.toProtoPrimitive)
          ) { case Some(svInfo) =>
            svInfo.name shouldBe getSvName(3)
          }
        },
      )
  }
}
