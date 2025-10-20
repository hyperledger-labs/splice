package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import com.digitalasset.canton.config.NonNegativeFiniteDuration

import java.time.Duration as JavaDuration

class SvTimeBasedBootstrappingRoundIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithSharedEnvironment {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(
        this.getClass.getSimpleName
      ) // no need to spin up 4 SVs to see whether automation works
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(roundZeroDuration = Some(NonNegativeFiniteDuration.ofHours(24)))
        )(config)
      )

  "round management with a 24 hour bootstrapping round" in { implicit env =>
    clue("Check bootstrapped rounds") {
      val rounds = getSortedOpenMiningRounds(sv1Backend.participantClientWithAdminToken, dsoParty)
      val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
      rounds should have size 3
      val round0 = rounds.head
      val round1 = rounds(1)
      val round2 = rounds(2)
      round0.data.round.number shouldBe 0
      round0.data.targetClosesAt shouldBe (now.toInstant plus JavaDuration.ofHours(24))
      round1.data.round.number shouldBe 1
      round1.data.targetClosesAt shouldBe (now.toInstant plus JavaDuration.ofHours(
        24
      ) plus defaultTickDuration.asJavaApproximation)
      round2.data.round.number shouldBe 2
      round2.data.opensAt shouldBe round0.data.targetClosesAt
      round2.data.targetClosesAt shouldBe (round2.data.opensAt plus defaultTickDuration.asJavaApproximation plus defaultTickDuration.asJavaApproximation)
    }

    actAndCheck("Advance time by one hour", advanceTime(JavaDuration.ofHours(1)))(
      // Note that this test might also just pass due to timing, but the latter tests would catch one round too many being closed.
      "round 0 does not close",
      _ =>
        getSortedIssuingRounds(
          sv1Backend.participantClientWithAdminToken,
          dsoParty,
        ) should have size 0,
    )

    actAndCheck(
      "Advancing time by another 23 hours",
      // add clock skew and one extra second as the targetClosesAt must be fully past the current time
      advanceTime(
        JavaDuration.ofHours(
          23
        ) plus sv1Backend.config.automation.clockSkewAutomationDelay.asJava plus JavaDuration
          .ofSeconds(1)
      ),
    )(
      "advances round 0 to become issuing",
      _ =>
        getSortedIssuingRounds(
          sv1Backend.participantClientWithAdminToken,
          dsoParty,
        ) should have size 1,
    )

    actAndCheck("Advancing time by another tick", advanceRoundsToNextRoundOpening)(
      "round 1 becomes issuing as well",
      _ =>
        getSortedIssuingRounds(
          sv1Backend.participantClientWithAdminToken,
          dsoParty,
        ) should have size 2,
    )
  }
}
