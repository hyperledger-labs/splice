package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.round.*
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.{SvTestUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

import java.time.{Duration as JavaDuration, Instant}
import scala.concurrent.duration.*

class SvTimeBasedIntegrationTestBase
    extends CNNodeIntegrationTest
    with SvTestUtil
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXCentralizedDomainWithSimTime(this.getClass.getSimpleName)
      .withManualStart
      // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the svc to consider unclaimed
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .addConfigTransforms((_, config) => {
        // TODO(M3-63) Currently, auto-expiration of unclaimed rewards is disabled by default, and enabled only where needed.
        // In the cluster it currently cannot be enabled due to lack of resiliency to unavailable validators
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableUnclaimedRewardExpiration).replace(true)
        )(config)
      })

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
      env: CNNodeTestConsoleEnvironment
  ): OpenMiningRoundsTriplet = {
    val rounds = getSortedOpenMiningRounds(
      svc.participantClientWithAdminToken,
      svcParty,
    )
    rounds should have length 3
    OpenMiningRoundsTriplet(rounds.head, rounds(1), rounds(2))
  }

  protected def advanceTimeAndCheckOpenRounds(
      toAdvanceAt: Instant
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = {
    val now = svc.participantClientWithAdminToken.ledger_api.time.get()
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
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = eventually() {
    val issuingRounds = getSortedIssuingRounds(svc.participantClientWithAdminToken, svcParty)
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
