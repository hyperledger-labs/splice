// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_OffboardSv
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

  "CopyVotesTrigger copies votes from source SV" in { implicit env =>
    def runTriggerOnSv2 =
      sv2Backend.dsoAutomation
        .trigger[CopyVotesTrigger]
        .runOnce()
        .futureValue

    val sv4Party = sv4Backend.getDsoInfo().svParty.toProtoPrimitive
    val action = new ARC_DsoRules(
      new SRARC_OffboardSv(new DsoRules_OffboardSv(sv4Party))
    )

    val (_, voteRequest) = actAndCheck(
      "sv1 creates a vote request to offboard sv4",
      sv1Backend.createVoteRequest(
        sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
        action,
        "url",
        "offboard sv4",
        sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        None,
      ),
    )(
      "vote request is created with sv1's accept vote",
      _ => {
        val vr = sv2Backend.listVoteRequests().loneElement
        vr.payload.votes.asScala should have size 1
        vr
      },
    )

    actAndCheck(
      "run CopyVotesTrigger on sv2", {
        runTriggerOnSv2
      },
    )(
      "sv2 copies sv1's accept vote",
      _ => {
        val vr = sv2Backend.listVoteRequests().loneElement
        val votes = vr.payload.votes.asScala
        votes should have size 2
        val sv2Vote = votes("Digital-Asset-Eng-2")
        sv2Vote.accept shouldBe true
        sv2Vote.reason.body should include("Copied from Digital-Asset-2")
      },
    )

    actAndCheck(
      "run CopyVotesTrigger again on sv2 (should be no-op)", {
        runTriggerOnSv2
      },
    )(
      "sv2 still has exactly one vote (no duplicate)",
      _ => {
        val vr = sv2Backend.listVoteRequests().loneElement
        val votes = vr.payload.votes.asScala
        votes should have size 2
      },
    )
  }

  "CopyVotesTrigger copies reject votes" in { implicit env =>
    def runTriggerOnSv2 =
      sv2Backend.dsoAutomation
        .trigger[CopyVotesTrigger]
        .runOnce()
        .futureValue

    val sv4Party = sv4Backend.getDsoInfo().svParty.toProtoPrimitive
    val action = new ARC_DsoRules(
      new SRARC_OffboardSv(new DsoRules_OffboardSv(sv4Party))
    )

    val (_, voteRequest) = actAndCheck(
      "sv3 creates a vote request to offboard sv4",
      sv3Backend.createVoteRequest(
        sv3Backend.getDsoInfo().svParty.toProtoPrimitive,
        action,
        "url",
        "offboard sv4",
        sv3Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        None,
      ),
    )(
      "vote request is created",
      _ => sv2Backend.listVoteRequests().loneElement,
    )

    actAndCheck(
      "sv1 casts a reject vote",
      sv1Backend.castVote(
        getTrackingId(voteRequest),
        false,
        "url",
        "I disagree",
      ),
    )(
      "sv1's reject vote is recorded",
      _ => {
        val vr = sv2Backend.listVoteRequests().loneElement
        vr.payload.votes.asScala should have size 2
      },
    )

    actAndCheck(
      "run CopyVotesTrigger on sv2", {
        runTriggerOnSv2
      },
    )(
      "sv2 copies sv1's reject vote",
      _ => {
        val vr = sv2Backend.listVoteRequests().loneElement
        val votes = vr.payload.votes.asScala
        votes should have size 3
        val sv2Vote = votes("Digital-Asset-Eng-2")
        sv2Vote.accept shouldBe false
        sv2Vote.reason.body should include("Copied from Digital-Asset-2")
        sv2Vote.reason.body should include("I disagree")
      },
    )

    actAndCheck(
      "sv1 changes its vote to accept",
      sv1Backend.castVote(
        getTrackingId(voteRequest),
        true,
        "new-url",
        "I changed my mind",
      ),
    )(
      "sv1's updated vote is recorded",
      _ => {
        val vr = sv2Backend.listVoteRequests().loneElement
        val votes = vr.payload.votes.asScala
        votes should have size 3
        votes("Digital-Asset-2").accept shouldBe true
      },
    )

    actAndCheck(
      "run CopyVotesTrigger on sv2 again to copy the updated vote", {
        runTriggerOnSv2
      },
    )(
      "sv2 updates its copied vote to match sv1",
      _ => {
        val vr = sv2Backend.listVoteRequests().loneElement
        val votes = vr.payload.votes.asScala
        votes should have size 3
        val sv2Vote = votes("Digital-Asset-Eng-2")
        sv2Vote.accept shouldBe true
        sv2Vote.reason.url shouldBe "new-url"
        sv2Vote.reason.body should include("Copied from Digital-Asset-2")
        sv2Vote.reason.body should include("I changed my mind")
      },
    )
  }

  "CopyVotesTrigger does nothing when source SV has not voted" in { implicit env =>
    val sv4Party = sv4Backend.getDsoInfo().svParty.toProtoPrimitive
    val action = new ARC_DsoRules(
      new SRARC_OffboardSv(new DsoRules_OffboardSv(sv4Party))
    )

    actAndCheck(
      "sv3 creates a vote request (sv1 has not voted on it)",
      sv3Backend.createVoteRequest(
        sv3Backend.getDsoInfo().svParty.toProtoPrimitive,
        action,
        "url",
        "offboard sv4",
        sv3Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        None,
      ),
    )(
      "vote request is visible to sv2",
      _ => sv2Backend.listVoteRequests().loneElement,
    )

    actAndCheck(
      "run CopyVotesTrigger on sv2 (should be no-op since sv1 hasn't voted)",
      sv2Backend.dsoAutomation.trigger[CopyVotesTrigger].runOnce().futureValue,
    )(
      "sv2 has not voted",
      _ => {
        val vr = sv2Backend.listVoteRequests().loneElement
        vr.payload.votes.asScala should have size 1
        vr.payload.votes.asScala.contains("Digital-Asset-Eng-2") shouldBe false
      },
    )
  }
}
