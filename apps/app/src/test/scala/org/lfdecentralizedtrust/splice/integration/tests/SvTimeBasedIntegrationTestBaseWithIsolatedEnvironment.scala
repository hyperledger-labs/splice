package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, TimeTestUtil, WalletTestUtil}

import java.time.{Instant, Duration as JavaDuration}
import scala.concurrent.duration.*

trait SvTimeBasedIntegrationTestUtil extends SvTestUtil with WalletTestUtil with TimeTestUtil {

  protected def readyToAdvanceAt(rounds: OpenMiningRoundsTriplet): Instant = {
    Ordering[Instant].max(
      rounds.oldestOpen.data.targetClosesAt,
      Ordering[Instant].max(
        rounds.middleOpen.data.opensAt plus SvUtil.fromRelTime(
          rounds.middleOpen.data.tickDuration
        ),
        rounds.latestOpen.data.opensAt,
      ),
    )
  }

  protected case class OpenMiningRoundsTriplet(
      oldestOpen: OpenMiningRound.Contract,
      middleOpen: OpenMiningRound.Contract,
      latestOpen: OpenMiningRound.Contract,
  )

  protected def getOpenMiningRounds()(implicit
      env: SpliceTestConsoleEnvironment
  ): OpenMiningRoundsTriplet = {
    val rounds = getSortedOpenMiningRounds(
      sv1Backend.participantClientWithAdminToken,
      dsoParty,
    )
    rounds should have length 3
    OpenMiningRoundsTriplet(rounds.head, rounds(1), rounds(2))
  }

  protected def advanceTimeAndCheckOpenRounds(
      toAdvanceAt: Instant
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
    val duration = JavaDuration.between(now.toInstant, toAdvanceAt)
    val timeShift = JavaDuration.ofSeconds(10)
    val skew = timeShift
    val rounds = getOpenMiningRounds()
    actAndCheck(
      s"advance time to shortly before the rounds should change",
      advanceTime(duration minus timeShift),
    )(
      s"waiting for ",
      _ =>
        always(durationOfSuccess = 1.second) {
          val newRounds = getOpenMiningRounds()
          newRounds.oldestOpen shouldBe rounds.oldestOpen
          newRounds.middleOpen shouldBe rounds.middleOpen
          newRounds.latestOpen shouldBe rounds.latestOpen
        },
    )

    actAndCheck(
      s"advancing time by duration of 2 * $timeShift and rounds should change",
      advanceTime(timeShift plus skew),
    )(
      s"waiting for ",
      _ =>
        eventually(5.seconds) {
          val newRounds = getOpenMiningRounds()
          newRounds.oldestOpen.data.round.number shouldBe rounds.middleOpen.data.round.number
          newRounds.middleOpen.data.round.number shouldBe rounds.latestOpen.data.round.number
          newRounds.latestOpen.data.round.number shouldBe rounds.latestOpen.data.round.number + 1L
        },
    )
  }

  protected def assertTickDurationOfIssuingRound(
      roundNumberToTickDuration: Map[Long, JavaDuration]
  )(implicit env: SpliceTestConsoleEnvironment): Unit = eventually() {
    val issuingRounds = getSortedIssuingRounds(sv1Backend.participantClientWithAdminToken, dsoParty)
    issuingRounds.map(_.data.round.number) shouldBe roundNumberToTickDuration.keySet.toSeq.sorted
    issuingRounds.map { issuingRound =>
      val expectedDuration = roundNumberToTickDuration(issuingRound.data.round.number)
      JavaDuration
        .between(
          issuingRound.data.opensAt,
          issuingRound.data.targetClosesAt,
        ) shouldBe (expectedDuration plus expectedDuration)
    }
  }
}

abstract class SvTimeBasedIntegrationTestBaseWithIsolatedEnvironmentWithElections
    extends IntegrationTest
    with SvTimeBasedIntegrationTestUtil {
  protected val baseEnvironmentDefinition: EnvironmentDefinition = EnvironmentDefinition
    .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
    .addConfigTransforms((_, config) =>
      ConfigTransforms.withPausedSvOffboardingMediatorAndPartyToParticipantTriggers()(
        config
      )
    )
    .withManualStart
    // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the dso to consider unclaimed
    .withoutAutomaticRewardsCollectionAndAmuletMerging
    .addConfigTransforms((_, config) =>
      updateAutomationConfig(ConfigurableApp.Sv)(
        // Since automatic rewards collection is disabled, closed rounds cannot be archived,
        // so tests that assert that closed rounds are archived fail.
        _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
      )(config)
    )

  override def environmentDefinition: SpliceEnvironmentDefinition =
    baseEnvironmentDefinition
}

abstract class SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironmentWithElections {
  override def environmentDefinition: EnvironmentDefinition =
    baseEnvironmentDefinition.withoutDsoDelegateReplacement
}

abstract class SvTimeBasedIntegrationTestBaseWithSharedEnvironment
    extends IntegrationTestWithSharedEnvironment
    with SvTimeBasedIntegrationTestUtil {
  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the dso to consider unclaimed
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .withoutDsoDelegateReplacement
}
