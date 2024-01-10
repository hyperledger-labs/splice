package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.SvAppBackendReference
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.ParticipantId

class SvDevNetReonboardingIntegrationTest extends SvIntegrationTestBase {

  override def environmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs((name, c) =>
          if (name == "sv4") {
            c.copy(
              onboarding = Some(conf.svApps(InstanceName.tryCreate("sv3")).onboarding.value)
            )
          } else {
            c
          }
        )(conf)
      )

  "Reonboarding an SV with the same name removes the old SV from PartyToParticipantX and the Decentralized Namespace" in {
    implicit env =>
      clue("SV3 and SV4 use different participants") {
        sv3Backend.participantClient.id should not be sv4Backend.participantClient.id
      }
      clue("Initialize SVC with 3 SVs") {
        startAllSync(
          sv1ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
          sv3ValidatorBackend,
        )
      }
      checkPartyToParticipantX(
        Seq(
          sv1Backend.participantClient.id,
          sv2Backend.participantClient.id,
          sv3Backend.participantClient.id,
        )
      )
      checkDecentralizedNamespace(
        Seq(sv1Backend, sv2Backend, sv3Backend)
      )

      sv3Backend.stop()
      sv3ValidatorBackend.stop()
      startAllSync(sv4Backend, sv4ValidatorBackend)

      checkPartyToParticipantX(
        Seq(
          sv1Backend.participantClient.id,
          sv2Backend.participantClient.id,
          sv4Backend.participantClient.id,
        )
      )
      checkDecentralizedNamespace(Seq(sv1Backend, sv2Backend, sv4Backend))

      def checkPartyToParticipantX(expected: Seq[ParticipantId]) = {
        eventually() {
          val mapping = sv1Backend.appState.participantAdminConnection
            .getPartyToParticipant(globalDomainId, sv1Backend.getSvcInfo().svcParty)
            .futureValue
            .mapping
          mapping.threshold shouldBe PositiveInt.one
          mapping.participants.map(_.participantId) should contain theSameElementsAs expected
        }
      }

      def checkDecentralizedNamespace(expected: Seq[SvAppBackendReference]) = {
        eventually() {
          val decentralizedNamespaces =
            sv1Backend.participantClient.topology.decentralized_namespaces
              .list(
                filterStore = globalDomainId.filterString,
                filterNamespace = svcParty.uid.namespace.toProtoPrimitive,
              )
          inside(decentralizedNamespaces) { case Seq(decentralizedNamespace) =>
            decentralizedNamespace.item.owners shouldBe expected
              .map(_.participantClient.id.uid.namespace)
              .toSet
          }
        }
      }
  }
}
