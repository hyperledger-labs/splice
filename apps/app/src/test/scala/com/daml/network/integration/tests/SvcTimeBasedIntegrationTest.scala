package com.daml.network.integration.tests

import com.daml.ledger.api.v1.ledger_offset.LedgerOffset
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.round.*
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import org.slf4j.event.Level
import monocle.macros.syntax.lens.*

import scala.jdk.CollectionConverters.*
import java.time.{Duration, Instant}
import com.daml.network.config.CoinConfigTransforms
import com.daml.network.util.Contract
import com.daml.network.util.CoinUtil.defaultCoinConfig
import com.digitalasset.canton.time.NonNegativeFiniteDuration

import scala.concurrent.duration.*

class SvcTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => {
        // Disable automatic reward collection, so that the wallet does not auto-collect rewards that we want the svc to consider unclaimed
        CoinConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
        )(config)
        // TODO(M3-63) Currently, auto-expiration of unclaimed rewards is disabled by default, and enabled only here.
        // In the cluster it currently cannot be enabled due to lack of resiliency to unavailable validators
        CoinConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableUnclaimedRewardExpiration).replace(true)
        )(config)
      })

  "round management" in { implicit env =>
    // Sync with background automation that onboards validator.
    eventually()({
      val rounds = getSortedOpenMiningRounds(svc.remoteParticipantWithAdminToken, svcParty)
      rounds.map {
        _.data.observers should have length 4
      }
      rounds should have size 3
    })

    // one tick - round 0 closes.
    advanceRoundsByOneTick
    eventually()(
      getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 1
    )
    // next tick - round 1 closes.
    advanceRoundsByOneTick
    eventually()(
      getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 2
    )
    // next tick - round 2 closes.
    advanceRoundsByOneTick
    eventually()(
      getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 3
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        val offsetBefore = svc.remoteParticipantWithAdminToken.ledger_api.transactions.end()
        // next tick - issuing round 0 can be closed
        // not using `advanceRoundsByOneTick` because this interferes with checking the state of the ClosedMiningRounds
        advanceTime(java.time.Duration.ofSeconds(160))
        eventually() {
          // Check for closing mining round in transactions instead of acs
          // to guard against automation archiving it concurrently.
          val transactions =
            svc.remoteParticipantWithAdminToken.ledger_api.transactions
              .treesJava(
                Set(svcParty),
                completeAfter = Int.MaxValue,
                beginOffset = offsetBefore,
                endOffset =
                  Some(new LedgerOffset().withBoundary(LedgerOffset.LedgerBoundary.LEDGER_END)),
              )
          val rounds =
            transactions.flatMap(DecodeUtil.decodeAllCreatedTree(ClosedMiningRound.COMPANION)(_))
          rounds should have size 1
        }
        eventually()( // .. hence even though a fourth issuing round is created, we end up with 3 active issuing rounds eventually.
          getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 3
        )
        // Advance time again to let automation kick-in and archive closed mining round
        // TODO(tech-debt): generalize and reuse in other tests
        advanceTime(
          env.actualConfig.svcApp
            .getOrElse(fail("svc backend config not found"))
            .automation
            .pollingInterval
            .duration
        )
        eventually()( // closed round is archived eventually.
          svc.remoteParticipantWithAdminToken.ledger_api.acs
            .filterJava(ClosedMiningRound.COMPANION)(svcParty) should have size 0
        )
      },
      entries => {
        forAtLeast(1, entries)(
          _.message should include(
            "successfully created the closed mining round with cid"
          )
        )
        forAtLeast(1, entries)(
          _.message should include(
            "successfully archived closed mining round"
          )
        )
      },
    )
  }

  "round management with scheduled config change of doubled tickDuration" in { implicit env =>
    val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()

    val defaultTickDuration = NonNegativeFiniteDuration(Duration.ofSeconds(150))
    val doubledTickDuration = NonNegativeFiniteDuration(Duration.ofSeconds(300))
    val configSchedule = new cc.schedule.Schedule(
      mkCoinConfig(defaultTickDuration),
      List(
        new Tuple2(
          now.add(Duration.ofSeconds(150)).toInstant,
          mkCoinConfig(doubledTickDuration),
        )
      ).asJava,
    )

    // set configSchedule
    setCoinConfigSchedule(configSchedule)
    advanceRoundsByOneTick

    // latest OpenMiningRound was created with doubled tick duration.
    eventually()({
      val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.coinConfig.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.middleOpen.data.coinConfig.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.latestOpen.data.coinConfig.tickDuration shouldBe toRelTime(doubledTickDuration)

      rounds.latestOpen.data.opensAt shouldBe (now + doubledTickDuration).toInstant
      rounds.latestOpen.data.targetClosesAt shouldBe (
        now + doubledTickDuration + doubledTickDuration + doubledTickDuration
      ).toInstant
    })

    clue("advance to OpenMiningRound 4") {
      // First IssuingRounds is active
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.duration
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      // As the latest round is with doubled tick duration,
      // latest.opensAt is expected to be after middle.opensAt + tickDuration and oldest.opensAt
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 5") {
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.duration,
          1L -> defaultTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.coinConfig.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.middleOpen.data.coinConfig.tickDuration shouldBe toRelTime(doubledTickDuration)
      rounds.latestOpen.data.coinConfig.tickDuration shouldBe toRelTime(doubledTickDuration)

      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt

      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 6") {
      assertTickDurationOfIssuingRound(
        Map(
          1L -> defaultTickDuration.duration,
          2L -> defaultTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      // all active open mining rounds are created with doubled tick
      rounds.oldestOpen.data.coinConfig.tickDuration shouldBe toRelTime(doubledTickDuration)
      rounds.middleOpen.data.coinConfig.tickDuration shouldBe toRelTime(doubledTickDuration)
      rounds.latestOpen.data.coinConfig.tickDuration shouldBe toRelTime(doubledTickDuration)

      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 7") {
      assertTickDurationOfIssuingRound(
        Map(
          2L -> defaultTickDuration.duration,
          3L -> doubledTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }
  }

  "round management with scheduled config change of reduced tickDuration" in { implicit env =>
    val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()

    val defaultTickDuration = NonNegativeFiniteDuration(Duration.ofSeconds(150))
    val reducedTickDuration = NonNegativeFiniteDuration(Duration.ofSeconds(75))
    val configSchedule = new cc.schedule.Schedule(
      mkCoinConfig(defaultTickDuration),
      List(
        new Tuple2(
          now.add(Duration.ofSeconds(150)).toInstant,
          mkCoinConfig(reducedTickDuration),
        )
      ).asJava,
    )

    // set configSchedule
    setCoinConfigSchedule(configSchedule)
    advanceRoundsByOneTick

    // latest OpenMiningRound was created with reduced tick duration.
    eventually()({
      val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.coinConfig.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.middleOpen.data.coinConfig.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.latestOpen.data.coinConfig.tickDuration shouldBe toRelTime(reducedTickDuration)

      rounds.latestOpen.data.opensAt shouldBe (now + reducedTickDuration).toInstant
      rounds.latestOpen.data.targetClosesAt shouldBe (
        now + reducedTickDuration + reducedTickDuration + reducedTickDuration
      ).toInstant
    })

    clue("advance to OpenMiningRound 4") {
      // First IssuingRounds is active
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.duration
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      // As tick duration of latestOpen is reduced,
      // latestOpen.opensAt is now before middleOpen + tickDuration
      // Instead of latestOpen.opensAt middleOpen + tickDuration becomes the time when it is ready to advance rounds
      expectedAdvanceRoundAt shouldBe (
        rounds.middleOpen.data.opensAt plus fromRelTime(
          rounds.middleOpen.data.coinConfig.tickDuration
        )
      )
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 5") {
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.duration,
          1L -> defaultTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.coinConfig.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.middleOpen.data.coinConfig.tickDuration shouldBe toRelTime(reducedTickDuration)
      rounds.latestOpen.data.coinConfig.tickDuration shouldBe toRelTime(reducedTickDuration)

      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      // As both tick durations of middleOpen and latestOpen are reduced,
      // latestOpen.opensAt and middleOpen + tickDuration are now before oldestOpen.targetCloseAt
      // oldestOpen.targetCloseAt becomes the time when it is ready to advance rounds
      expectedAdvanceRoundAt shouldBe rounds.oldestOpen.data.targetClosesAt

      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 6") {
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.duration,
          1L -> defaultTickDuration.duration,
          2L -> defaultTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      // all active open mining rounds are created with reduced tick
      rounds.oldestOpen.data.coinConfig.tickDuration shouldBe toRelTime(reducedTickDuration)
      rounds.middleOpen.data.coinConfig.tickDuration shouldBe toRelTime(reducedTickDuration)
      rounds.latestOpen.data.coinConfig.tickDuration shouldBe toRelTime(reducedTickDuration)

      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      // rounds.latestOpen is the time when it is ready to advance rounds
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 7") {
      // Issuing mining rounds created earlier rounds have a longer tick duration
      // Therefore, when issuing mining rounds with reduced tick duration is created later,
      // There are still issuing mining rounds with longer tick duration not yet closed
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.duration,
          1L -> defaultTickDuration.duration,
          2L -> defaultTickDuration.duration,
          3L -> reducedTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 8") {
      // Issuing mining rounds created earlier rounds have a longer tick duration
      // Therefore, when issuing mining rounds with reduced tick duration is created later,
      // There are still issuing mining rounds with longer tick duration not yet closed
      assertTickDurationOfIssuingRound(
        Map(
          1L -> defaultTickDuration.duration,
          2L -> defaultTickDuration.duration,
          3L -> reducedTickDuration.duration,
          4L -> reducedTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 9") {
      // Issuing mining rounds created earlier rounds have a longer tick duration
      // Therefore, when issuing mining rounds with reduced tick duration is created later,
      // There are still issuing mining rounds with longer tick duration not yet closed
      assertTickDurationOfIssuingRound(
        Map(
          1L -> defaultTickDuration.duration,
          2L -> defaultTickDuration.duration,
          3L -> reducedTickDuration.duration,
          4L -> reducedTickDuration.duration,
          5L -> reducedTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 10") {
      // Issuing mining rounds created earlier rounds have a longer tick duration
      // Therefore, when issuing mining rounds with reduced tick duration is created later,
      // There are still issuing mining rounds with longer tick duration not yet closed
      assertTickDurationOfIssuingRound(
        Map(
          2L -> defaultTickDuration.duration,
          4L -> reducedTickDuration.duration,
          5L -> reducedTickDuration.duration,
          6L -> reducedTickDuration.duration,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }
  }

  "calculation of issuance per coin" in { implicit env =>
    // 3 unfeatured app rewards & 3 featured app rewards & 3 validator rewards, 2 of each for round 0 and one for round 1
    // to check we sum up but only for the right round.
    val rewards = Seq(
      // featured app rewards for a total of 200.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(1.0).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(199.0).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(3.0).bigDecimal,
        new Round(1),
      ),
      // unfeatured app rewards for a total of 9800.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(2.5).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(9797.5).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(5.0).bigDecimal,
        new Round(1),
      ),
      // validator rewards for a total of 10000.0 in round 0
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(3.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(9997.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(15.0).bigDecimal,
        new Round(1),
      ),
    )
    // Create a bunch of rewards directly
    svc.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.flatMap(_.create.commands.asScala.toSeq),
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        advanceRoundsByOneTick
        eventually() {
          getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 1
        }
      },
      entries =>
        forAtLeast(1, entries)(
          _.message should include(
            s"completed summarizing mining round with com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary(10000.0000000000, 200.0000000000, 9800.0000000000)"
          )
        ),
    )

    def decimal(d: Double): java.math.BigDecimal = BigDecimal(d).setScale(10).bigDecimal
    val issuingRounds = getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty)

    inside(issuingRounds) { case Seq(issuingRound) =>
      issuingRound.data.issuancePerValidatorRewardCoupon shouldBe decimal(0.2000000000)
      issuingRound.data.issuancePerFeaturedAppRewardCoupon shouldBe decimal(100.0000000000)
      issuingRound.data.issuancePerUnfeaturedAppRewardCoupon shouldBe decimal(0.6000000000)
    }
  }

  "auto-merge unclaimed rewards" in { implicit env =>
    val threshold =
      10 // TODO(M3-46): base this on the actual threshold read from the svcRules config
    val numRewards = threshold + 1
    val rewardAmount = 0.1

    def getUnclaimedRewardContracts() = svc.remoteParticipantWithAdminToken.ledger_api.acs
      .filterJava(UnclaimedReward.COMPANION)(svcParty)

    val existingUnclaimedRewards = getUnclaimedRewardContracts().length

    actAndCheck(
      s"Create as many unclaimed rewards as needed to have at least ${numRewards}", {
        val unclaimedRewards = ((existingUnclaimedRewards + 1) to numRewards).map(_ =>
          new UnclaimedReward(svcParty.toProtoPrimitive, BigDecimal(rewardAmount).bigDecimal)
        )
        if (!unclaimedRewards.isEmpty) {
          svc.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
            actAs = Seq(svcParty),
            optTimeout = None,
            commands = unclaimedRewards.flatMap(_.create.commands.asScala.toSeq),
          )
        }
      },
    )(
      "Wait for the unclaimed rewards to get merged automagically",
      _ => {
        advanceTime(Duration.ofSeconds(1))
        getUnclaimedRewardContracts().length should (be < threshold)
      },
    )
  }

  "collect expired reward coupons" in { implicit env =>
    def getNumRewardCoupons(
        round: Contract[OpenMiningRound.ContractId, OpenMiningRound]
    ): Int = {
      svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(AppRewardCoupon.COMPANION)(
          svcParty,
          co => co.data.round.number == round.payload.round.number,
        )
        .length +
        svc.remoteParticipantWithAdminToken.ledger_api.acs
          .filterJava(ValidatorRewardCoupon.COMPANION)(
            svcParty,
            co => co.data.round.number == round.payload.round.number,
          )
          .length
    }

    val round = scan.getTransferContext().latestOpenMiningRound
    // There may be rewards left over from other tests, so we first check the
    // number of existing ones, and compare to that below
    val numRewards = getNumRewardCoupons(round)

    val (aliceParty, bobParty) = onboardAliceAndBob()
    aliceWallet.tap(100.0)
    bobWallet.tap(100.0)

    actAndCheck(
      "Generate some reward coupons by executing a few direct transfers", {
        p2pTransfer(aliceWallet, bobWallet, bobParty, 10.0)
        p2pTransfer(aliceWallet, bobWallet, bobParty, 10.0)
        p2pTransfer(bobWallet, aliceWallet, aliceParty, 10.0)
        p2pTransfer(bobWallet, aliceWallet, aliceParty, 10.0)
      },
    )(
      "Wait for all reward coupons to be created",
      _ => {
        advanceTime(Duration.ofSeconds(1))
        getNumRewardCoupons(round) should be(numRewards + 8) // 4 app rewards + 4 validator
      },
    )

    actAndCheck(
      "Advance 5 ticks, to close the round",
      (1 to 5).foreach(_ => advanceRoundsByOneTick),
    )(
      "Wait for all unclaimed coupons to be archived and the closed round to be archived",
      _ => {
        advanceTime(Duration.ofSeconds(1))
        getNumRewardCoupons(round) should be(0)
        scan
          .getClosedRounds()
          .filter(r => r.payload.round.number == round.payload.round.number) should be(empty)
      },
    )
  }

  private def mkCoinConfig(
      tickDuration: NonNegativeFiniteDuration
  ): cc.coinconfig.CoinConfig[cc.coinconfig.USD] =
    defaultCoinConfig(
      NonNegativeFiniteDuration.ofMicros(tickDuration.getMicros),
      100,
    )

  private def toRelTime(duration: NonNegativeFiniteDuration): RelTime = new RelTime(
    duration.getMicros
  )

  private def fromRelTime(duration: RelTime): Duration =
    Duration.ofMillis(duration.microseconds / 1000)

  private def setCoinConfigSchedule(
      configSchedule: cc.schedule.Schedule[Instant, cc.coinconfig.CoinConfig[cc.coinconfig.USD]]
  )(implicit env: CoinTestConsoleEnvironment) = {
    val coinRules = svc.remoteParticipantWithAdminToken.ledger_api.acs
      .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
    coinRules should have length 1
    svc.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands =
        coinRules.head.id.exerciseCoinRules_SetConfigSchedule(configSchedule).commands.asScala.toSeq,
    )
  }

  private case class OpenMiningRoundsTriplet(
      oldestOpen: OpenMiningRound.Contract,
      middleOpen: OpenMiningRound.Contract,
      latestOpen: OpenMiningRound.Contract,
  )

  private def getOpenMiningRounds()(implicit
      env: CoinTestConsoleEnvironment
  ): OpenMiningRoundsTriplet = {
    val rounds = getSortedOpenMiningRounds(
      svc.remoteParticipantWithAdminToken,
      svcParty,
    )
    rounds should have length 3
    OpenMiningRoundsTriplet(rounds.head, rounds(1), rounds(2))
  }

  private def readyToAdvanceAt(rounds: OpenMiningRoundsTriplet): Instant = {
    Ordering[Instant].max(
      rounds.oldestOpen.data.targetClosesAt,
      Ordering[Instant].max(
        rounds.middleOpen.data.opensAt plus fromRelTime(
          rounds.middleOpen.data.coinConfig.tickDuration
        ),
        rounds.latestOpen.data.opensAt,
      ),
    )
  }

  private def advanceTimeAndCheckOpenRounds(
      toAdvanceAt: Instant
  )(implicit env: CoinTestConsoleEnvironment): Unit = {
    val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()
    val duration = Duration.between(now.toInstant, toAdvanceAt)
    val timeShift = Duration.ofSeconds(10)
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

  private def assertTickDurationOfIssuingRound(
      roundNumberToTickDuration: Map[Long, Duration]
  )(implicit env: CoinTestConsoleEnvironment): Unit = eventually() {
    val issuingRounds = getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty)
    issuingRounds.map(_.data.round.number) shouldBe roundNumberToTickDuration.keySet.toSeq.sorted
    issuingRounds.map { issuingRound =>
      val expectedDuration = roundNumberToTickDuration(issuingRound.data.round.number)
      Duration
        .between(
          issuingRound.data.opensAt,
          issuingRound.data.targetClosesAt,
        ) shouldBe (expectedDuration plus expectedDuration)
    }
  }
}
