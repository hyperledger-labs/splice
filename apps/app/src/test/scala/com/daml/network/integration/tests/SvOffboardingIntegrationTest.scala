package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_RemoveMember

class SvOffboardingIntegrationTest extends SvIntegrationTestBase {

  "At least 3 SV votes are required to remove a member in devnet" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    val (sv5Party, _) = actAndCheck(
      "Add 1 phantom SVs to SVC", {
        val sv5Name = "sv5"
        val sv5PartyId = allocateRandomSvParty(sv5Name)
        addSvMember(sv5PartyId, sv5Name)
        sv5PartyId.toProtoPrimitive
      },
    )(
      "There should be 5 SVC members in total now",
      _ => {
        sv1.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    val (_, voteRequestCid) = actAndCheck(
      "SV1 create a vote request to remove sv5", {
        val action: ActionRequiringConfirmation =
          new ARC_SvcRules(new SRARC_RemoveMember(new SvcRules_RemoveMember(sv5Party)))
        sv1.createVoteRequest(
          sv1.getSvcInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
        )
      },
    )(
      "The vote request has been created and SV1 accepts as he created it",
      _ => {
        sv1.listVoteRequests() should not be empty
        val head = sv1.listVoteRequests().head.contractId
        sv1.listVotes(Vector(head.contractId)) should have size 1
        head
      },
    )

    actAndCheck(
      "SV2 votes on removing sv5", {
        sv2.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not remove sv5",
      _ => {
        sv2.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    actAndCheck(
      "SV3 refuses to remove sv5", {
        sv3.castVote(voteRequestCid, false, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not remove sv5",
      _ => {
        sv3.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    actAndCheck(
      "SV4 votes on removing sv5", {
        sv4.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should remove sv5",
      _ => {
        sv4.getSvcInfo().svcRules.payload.members should have size 4
      },
    )

  }

}
