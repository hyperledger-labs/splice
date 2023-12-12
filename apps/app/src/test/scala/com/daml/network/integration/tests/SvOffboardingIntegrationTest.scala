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

  "At least 4 SV votes are required to remove a member in a 5 members quorum" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    val (sv5PartyId, _) = actAndCheck(
      "Add 1 phantom SVs to SVC", {
        val sv5Name = "sv5"
        val sv5PartyId = allocateRandomSvParty(sv5Name)
        addSvMember(sv5PartyId, sv5Name, sv1Backend.participantClient.id)
        sv5PartyId
      },
    )(
      "There should be 5 SVC members in total now",
      _ => {
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    val (_, voteRequestCid) = actAndCheck(
      "SV1 create a vote request to remove sv5", {
        val action: ActionRequiringConfirmation =
          new ARC_SvcRules(
            new SRARC_RemoveMember(new SvcRules_RemoveMember(sv5PartyId.toProtoPrimitive))
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
      "The vote request has been created and SV1 accepts as he created it and all other SVs observe it",
      _ => {
        svs.foreach { sv =>
          sv.listVoteRequests() should not be empty
        }
        val head = sv1Backend.listVoteRequests().head.contractId
        sv1Backend.listVotes(Vector(head.contractId)) should have size 1
        head
      },
    )

    actAndCheck(
      "SV2 votes on removing sv5", {
        sv2Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not remove sv5",
      _ => {
        sv2Backend.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    actAndCheck(
      "SV3 votes on removing sv5", {
        sv3Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not remove sv5",
      _ => {
        sv3Backend.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    actAndCheck(
      "SV4 votes on removing sv5", {
        sv4Backend.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should remove sv5",
      _ => {
        sv4Backend.getSvcInfo().svcRules.payload.members should have size 4
        val mapping = sv4Backend.appState.participantAdminConnection
          .getPartyToParticipant(globalDomainId, sv4Backend.getSvcInfo().svcParty)
          .futureValue
          .mapping
        mapping.participants.map(_.participantId.uid.namespace) should not contain (
          sv5PartyId.uid.namespace
        )

      },
    )

  }

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
        sv1Backend.listVoteRequests().head.contractId
      },
    )

    clue("SV2 votes on removing sv4") {
      sv2Backend.castVote(voteRequestCid4, true, "url", "description")
    }

    actAndCheck(
      "SV3 votes on removing sv4", {
        sv3Backend.castVote(voteRequestCid4, true, "url", "description")
      },
    )(
      "The majority voted yet, thus the trigger should remove the svc party hosting for sv4",
      _ => {
        val mapping = sv3Backend.appState.participantAdminConnection
          .getPartyToParticipant(globalDomainId, sv3Backend.getSvcInfo().svcParty)
          .futureValue
          .mapping
        mapping.threshold shouldBe PositiveInt.tryCreate(1)
        mapping.participants.map(_.participantId.uid.namespace) should not contain (sv4Backend
          .getSvcInfo()
          .svParty
          .uid
          .namespace)
        sv3Backend.getSvcInfo().svcRules.payload.offboardedMembers.keySet() should contain(
          sv4Backend.getSvcInfo().svParty.toProtoPrimitive
        )
      },
    )
  }

}
