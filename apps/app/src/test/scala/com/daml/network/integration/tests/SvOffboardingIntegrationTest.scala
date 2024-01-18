package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_RemoveMember
import com.daml.network.integration.plugins.UseInMemoryStores
import com.digitalasset.canton.config.RequireTypes.PositiveInt

class InMemorySvOffboardingIntegrationTest extends SvOffboardingIntegrationTest {
  registerPlugin(new UseInMemoryStores(loggerFactory))
}

class SvOffboardingIntegrationTest extends SvIntegrationTestBase {

  "Off-boarding SV4 updates the topology states" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      initSvc()
    }

    val (_, voteRequestCid4) = actAndCheck(
      "SV1 create a vote request to remove sv4", {
        val action: ActionRequiringConfirmation =
          new ARC_SvcRules(
            new SRARC_RemoveMember(
              new SvcRules_RemoveMember(sv4Backend.getSvcInfo().svParty.toProtoPrimitive)
            )
          )
        sv1Backend.createVoteRequest(
          sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
          sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
        )
      },
    )(
      "The vote request has been created",
      _ => {
        val voteRequestCid = sv1Backend.listVoteRequests().headOption.value.contractId
        svs.foreach { sv =>
          sv.listVoteRequests().headOption.value.contractId shouldBe voteRequestCid
        }
        voteRequestCid
      },
    )

    actAndCheck(
      "SV2 votes on removing sv4", {
        sv2Backend.castVote(voteRequestCid4, true, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not remove sv4",
      _ => {
        sv3Backend.getSvcInfo().svcRules.payload.members should have size 4
      },
    )

    actAndCheck(
      "SV3 votes on removing sv4", {
        sv3Backend.castVote(voteRequestCid4, true, "url", "description")
      },
    )(
      "The majority voted yet, thus the trigger should remove the svc party hosting for sv4",
      _ => {
        sv3Backend.getSvcInfo().svcRules.payload.members should have size 3
        suppressFailedClues(loggerFactory) {
          clue("Check partyToParticipant offboarding") {
            val mapping = sv3Backend.appState.participantAdminConnection
              .getPartyToParticipant(globalDomainId, sv3Backend.getSvcInfo().svcParty)
              .futureValue
              .mapping
            mapping.threshold shouldBe PositiveInt.tryCreate(2)
            mapping.participants.map(_.participantId.uid.namespace) should not contain sv4Backend
              .getSvcInfo()
              .svParty
              .uid
              .namespace
            sv3Backend.getSvcInfo().svcRules.payload.offboardedMembers.keySet() should contain(
              sv4Backend.getSvcInfo().svParty.toProtoPrimitive
            )
          }

          clue("Check decentralized namespace offboarding") {
            val decentralizedNamespaces =
              sv1Backend.participantClient.topology.decentralized_namespaces
                .list(
                  filterStore = globalDomainId.filterString,
                  filterNamespace = svcParty.uid.namespace.toProtoPrimitive,
                )
            inside(decentralizedNamespaces) { case Seq(decentralizedNamespace) =>
              decentralizedNamespace.item.owners shouldBe Seq(
                sv1Backend,
                sv2Backend,
                sv3Backend,
              )
                .map(_.participantClient.id.uid.namespace)
                .toSet
            }
          }
        }
      },
    )
  }
}
