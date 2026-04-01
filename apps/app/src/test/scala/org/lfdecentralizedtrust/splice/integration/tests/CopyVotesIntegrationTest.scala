// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_AddSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_AddSv
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.CopyVotesTrigger
import org.lfdecentralizedtrust.splice.util.SvTestUtil

import scala.jdk.CollectionConverters.*

class CopyVotesIntegrationTest extends IntegrationTestWithIsolatedEnvironment with SvTestUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, config) =>
          ConfigTransforms.updateAllSvAppConfigs { (name, svConfig) =>
            if (name == "sv2")
              svConfig.copy(copyVotesFrom = Some("Digital-Asset-2"))
            else svConfig
          }(config),
        (_, config) => ConfigTransforms.withNoVoteCooldown(config),
        (_, config) =>
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
            _.withPausedTrigger[CopyVotesTrigger]
          )(config),
      )

  "CopyVotesTrigger mirrors source votes across accept, reject, update, and no-op cases" in {
    implicit env =>
      val _ = env

      def copyVotesTrigger = sv2Backend.dsoAutomation.trigger[CopyVotesTrigger]

      def resumeTriggerAndCheck(assertion: => org.scalatest.compatible.Assertion) = {
        copyVotesTrigger.resume()
        try
          eventually() {
            assertion
          }
        finally copyVotesTrigger.pause().futureValue
      }

      def voteRequestOnSv2(
          trackingId: org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequest.ContractId
      ) =
        sv2Backend
          .listVoteRequests()
          .find(vr => getTrackingId(vr) == trackingId)
          .value

      def voteRequestOnSv2ByBody(body: String) =
        sv2Backend
          .listVoteRequests()
          .find(_.payload.reason.body == body)
          .value

      def addSvAction(index: Int) = new ARC_DsoRules(
        new SRARC_AddSv(
          new DsoRules_AddSv(
            s"copy-vote-host-$index",
            s"Copy Vote Candidate $index",
            1000L + index,
            s"copy-vote-participant-$index",
            new Round(100L + index),
          )
        )
      )

      val acceptAction = addSvAction(1)
      val rejectAction = addSvAction(2)
      val noVoteAction = addSvAction(3)

      val (_, acceptVoteRequest) = actAndCheck(
        "sv1 creates the first vote request",
        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          acceptAction,
          "url",
          "copy vote request 1",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        ),
      )(
        "the initial vote request is visible to sv2",
        _ => {
          val vr = sv2Backend.listVoteRequests().loneElement
          vr.payload.votes.asScala should have size 1
          vr
        },
      )
      val acceptTrackingId = getTrackingId(acceptVoteRequest)

      actAndCheck(
        "resume CopyVotesTrigger on sv2 for the initial accept vote",
        (),
      )(
        "sv2 copies sv1's accept vote",
        _ =>
          resumeTriggerAndCheck {
            val vr = voteRequestOnSv2(acceptTrackingId)
            val votes = vr.payload.votes.asScala
            votes should have size 2
            val sv2Vote = votes("Digital-Asset-Eng-2")
            sv2Vote.accept shouldBe true
            sv2Vote.reason.body should include("Automatically Copied from Digital-Asset-2")
          },
      )

      actAndCheck(
        "resume CopyVotesTrigger again on sv2 for the same vote",
        (),
      )(
        "sv2 does not create a duplicate vote",
        _ =>
          resumeTriggerAndCheck {
            val vr = voteRequestOnSv2(acceptTrackingId)
            vr.payload.votes.asScala should have size 2
          },
      )

      val (_, rejectVoteRequest) = actAndCheck(
        "sv3 creates a second vote request",
        sv3Backend.createVoteRequest(
          sv3Backend.getDsoInfo().svParty.toProtoPrimitive,
          rejectAction,
          "url",
          "copy vote request 2",
          sv3Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        ),
      )(
        "the second vote request is visible to sv2",
        _ => {
          val vr = voteRequestOnSv2ByBody("copy vote request 2")
          vr.payload.votes.asScala should have size 1
          vr
        },
      )
      val rejectTrackingId = getTrackingId(rejectVoteRequest)

      actAndCheck(
        "sv1 casts a reject vote on the second request",
        sv1Backend.castVote(
          getTrackingId(rejectVoteRequest),
          false,
          "url",
          "I disagree",
        ),
      )(
        "sv1's reject vote is recorded",
        _ => {
          val vr = voteRequestOnSv2(rejectTrackingId)
          vr.payload.votes.asScala should have size 2
        },
      )

      actAndCheck(
        "resume CopyVotesTrigger on sv2 for the reject vote",
        (),
      )(
        "sv2 copies sv1's reject vote",
        _ =>
          resumeTriggerAndCheck {
            val vr = voteRequestOnSv2(rejectTrackingId)
            val votes = vr.payload.votes.asScala
            votes should have size 3
            val sv2Vote = votes("Digital-Asset-Eng-2")
            sv2Vote.accept shouldBe false
            sv2Vote.reason.body should include("Automatically Copied from Digital-Asset-2")
            sv2Vote.reason.body should include("I disagree")
          },
      )

      actAndCheck(
        "sv1 changes its vote on the second request to accept",
        sv1Backend.castVote(
          getTrackingId(rejectVoteRequest),
          true,
          "new-url",
          "I changed my mind",
        ),
      )(
        "sv1's updated vote is recorded",
        _ => {
          val vr = voteRequestOnSv2(rejectTrackingId)
          val votes = vr.payload.votes.asScala
          votes should have size 3
          votes("Digital-Asset-2").accept shouldBe true
        },
      )

      actAndCheck(
        "resume CopyVotesTrigger on sv2 again for the updated vote",
        (),
      )(
        "sv2 updates its copied vote to match sv1",
        _ =>
          resumeTriggerAndCheck {
            val vr = voteRequestOnSv2(rejectTrackingId)
            val votes = vr.payload.votes.asScala
            votes should have size 3
            val sv2Vote = votes("Digital-Asset-Eng-2")
            sv2Vote.accept shouldBe true
            sv2Vote.reason.url shouldBe "new-url"
            sv2Vote.reason.body should include("Automatically Copied from Digital-Asset-2")
            sv2Vote.reason.body should include("I changed my mind")
          },
      )

      val (_, noVoteRequest) = actAndCheck(
        "sv3 creates a third vote request that sv1 has not voted on",
        sv3Backend.createVoteRequest(
          sv3Backend.getDsoInfo().svParty.toProtoPrimitive,
          noVoteAction,
          "url",
          "copy vote request 3",
          sv3Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        ),
      )(
        "the third vote request is visible to sv2",
        _ => {
          val vr = voteRequestOnSv2ByBody("copy vote request 3")
          vr.payload.votes.asScala should have size 1
          vr
        },
      )
      val noVoteTrackingId = getTrackingId(noVoteRequest)

      actAndCheck(
        "resume CopyVotesTrigger on sv2 when sv1 has not voted",
        (),
      )(
        "sv2 does not create a vote for the third request",
        _ =>
          resumeTriggerAndCheck {
            val vr = voteRequestOnSv2(noVoteTrackingId)
            vr.payload.votes.asScala should have size 1
            vr.payload.votes.asScala.contains("Digital-Asset-Eng-2") shouldBe false
          },
      )
  }
}
