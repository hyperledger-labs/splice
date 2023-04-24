package com.daml.network.integration.tests

import com.daml.ledger.api.v1.ledger_offset.LedgerOffset
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.round.*
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.LocalCNNodeAppReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.CNNodeUtil.defaultIssuanceCurve
import com.daml.network.util.{Contract, SvTestUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import com.digitalasset.canton.time.EnrichedDurations.*
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

import scala.math.pow
import java.math.RoundingMode
import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SvTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with SvTestUtil
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
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

  "round management" in { implicit env =>
    initSvc()

    // Sync with background automation that onboards validator.
    eventually()({
      val rounds = getSortedOpenMiningRounds(svc.remoteParticipantWithAdminToken, svcParty)
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

    val offsetBefore = svc.remoteParticipantWithAdminToken.ledger_api.transactions.end()
    // next tick - issuing round 0 can be closed
    // not using `advanceRoundsByOneTick` because this interferes with checking the state of the ClosedMiningRounds
    advanceTime(tickDurationWithBuffer)
    eventually() {
      // Check for closing mining round in transactions instead of acs
      // to guard against automation archiving it concurrently.
      val transactions =
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.transactions
          .treesJava(
            Set(svcParty),
            completeAfter = Int.MaxValue,
            beginOffset = offsetBefore,
            endOffset =
              Some(new LedgerOffset().withBoundary(LedgerOffset.LedgerBoundary.LEDGER_END)),
          )
      val rounds =
        transactions.flatMap(
          DecodeUtil.decodeAllCreatedTree(cc.round.ClosedMiningRound.COMPANION)(_)
        )
      rounds should have size 1
    }
    eventually()( // .. hence even though a fourth issuing round is created, we end up with 3 active issuing rounds eventually.
      getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 3
    )

    // advance time by 2 polling intervals to ensure that the automations
    // to create confirmation contracts for archival and then executing the confirmations
    // have both run
    advanceTimeByPollingInterval(sv1)
    advanceTimeByPollingInterval(sv1)
    clue("Wait until the closed round is archived") {
      eventually()(
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.round.ClosedMiningRound.COMPANION)(svcParty) should have size 0
      )
    }

  }

  "round management with scheduled config change of doubled tickDuration" in { implicit env =>
    initSvc()

    val doubledTickDuration = NonNegativeFiniteDuration.ofSeconds(300)
    svcClient.setConfigSchedule(
      createConfigSchedule(
        (defaultTickDuration.asJavaApproximation, mkCoinConfig(doubledTickDuration))
      )
    )
    advanceRoundsByOneTick

    // latest OpenMiningRound was created with doubled tick duration.
    eventually()({
      val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe toRelTime(doubledTickDuration)

      rounds.latestOpen.data.opensAt shouldBe (now + doubledTickDuration.toInternal).toInstant
      rounds.latestOpen.data.targetClosesAt shouldBe (
        now + doubledTickDuration.toInternal + doubledTickDuration.toInternal + doubledTickDuration.toInternal
      ).toInstant
    })

    clue("advance to OpenMiningRound 4") {
      // First IssuingRounds is active
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.asJavaApproximation
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
          0L -> defaultTickDuration.asJavaApproximation,
          1L -> defaultTickDuration.asJavaApproximation,
        )
      )

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe toRelTime(doubledTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe toRelTime(doubledTickDuration)

      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt

      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 6") {
      assertTickDurationOfIssuingRound(
        Map(
          1L -> defaultTickDuration.asJavaApproximation,
          2L -> defaultTickDuration.asJavaApproximation,
        )
      )

      val rounds = getOpenMiningRounds()
      // all active open mining rounds are created with doubled tick
      rounds.oldestOpen.data.tickDuration shouldBe toRelTime(doubledTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe toRelTime(doubledTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe toRelTime(doubledTickDuration)

      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 7") {
      assertTickDurationOfIssuingRound(
        Map(
          2L -> defaultTickDuration.asJavaApproximation,
          3L -> doubledTickDuration.asJavaApproximation,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }
  }

  "round management with scheduled config change of reduced tickDuration" in { implicit env =>
    initSvc()

    val reducedTickDuration = NonNegativeFiniteDuration.ofSeconds(75)
    svcClient.setConfigSchedule(
      createConfigSchedule(
        (defaultTickDuration.asJavaApproximation, mkCoinConfig(reducedTickDuration))
      )
    )
    advanceRoundsByOneTick

    // latest OpenMiningRound was created with reduced tick duration.
    eventually()({
      val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe toRelTime(reducedTickDuration)

      rounds.latestOpen.data.opensAt shouldBe (now + reducedTickDuration.toInternal).toInstant
      rounds.latestOpen.data.targetClosesAt shouldBe (
        now + reducedTickDuration.toInternal + reducedTickDuration.toInternal + reducedTickDuration.toInternal
      ).toInstant
    })

    clue("advance to OpenMiningRound 4") {
      // First IssuingRounds is active
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.asJavaApproximation
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      // As tick duration of latestOpen is reduced,
      // latestOpen.opensAt is now before middleOpen + tickDuration
      // Instead of latestOpen.opensAt middleOpen + tickDuration becomes the time when it is ready to advance rounds
      expectedAdvanceRoundAt shouldBe (
        rounds.middleOpen.data.opensAt plus fromRelTime(
          rounds.middleOpen.data.tickDuration
        )
      )
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 5") {
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.asJavaApproximation,
          1L -> defaultTickDuration.asJavaApproximation,
        )
      )

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.tickDuration shouldBe toRelTime(defaultTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe toRelTime(reducedTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe toRelTime(reducedTickDuration)

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
          0L -> defaultTickDuration.asJavaApproximation,
          1L -> defaultTickDuration.asJavaApproximation,
          2L -> defaultTickDuration.asJavaApproximation,
        )
      )

      val rounds = getOpenMiningRounds()
      // all active open mining rounds are created with reduced tick
      rounds.oldestOpen.data.tickDuration shouldBe toRelTime(reducedTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe toRelTime(reducedTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe toRelTime(reducedTickDuration)

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
          0L -> defaultTickDuration.asJavaApproximation,
          1L -> defaultTickDuration.asJavaApproximation,
          2L -> defaultTickDuration.asJavaApproximation,
          3L -> reducedTickDuration.asJavaApproximation,
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
          1L -> defaultTickDuration.asJavaApproximation,
          2L -> defaultTickDuration.asJavaApproximation,
          3L -> reducedTickDuration.asJavaApproximation,
          4L -> reducedTickDuration.asJavaApproximation,
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
          1L -> defaultTickDuration.asJavaApproximation,
          2L -> defaultTickDuration.asJavaApproximation,
          3L -> reducedTickDuration.asJavaApproximation,
          4L -> reducedTickDuration.asJavaApproximation,
          5L -> reducedTickDuration.asJavaApproximation,
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
          2L -> defaultTickDuration.asJavaApproximation,
          4L -> reducedTickDuration.asJavaApproximation,
          5L -> reducedTickDuration.asJavaApproximation,
          6L -> reducedTickDuration.asJavaApproximation,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }
  }

  "round management with very tightly scheduled config" in { implicit env =>
    initSvc()

    val config101 = mkCoinConfig(defaultTickDuration, 101)
    val config102 = mkCoinConfig(defaultTickDuration, 102)

    svcClient.setConfigSchedule(
      createConfigSchedule(
        (Duration.ofSeconds(150), config101),
        (Duration.ofSeconds(151), config102),
      )
    )

    advanceRoundsByOneTick
    advanceRoundsByOneTick

    // config101 is never used as there is no round created at a time between now + 150 and now + 151 seconds
    eventually()({
      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.transferConfigUsd.maxNumInputs shouldBe 100
      rounds.middleOpen.data.transferConfigUsd.maxNumInputs shouldBe config102.transferConfig.maxNumInputs
      rounds.latestOpen.data.transferConfigUsd.maxNumInputs shouldBe config102.transferConfig.maxNumInputs
    })

    val config201 = mkCoinConfig(defaultTickDuration, 201)
    val config202 = mkCoinConfig(defaultTickDuration, 202)

    {
      val now = svc.remoteParticipantWithAdminToken.ledger_api.time.get()
      val configSchedule = {
        new cc.schedule.Schedule(
          mkCoinConfig(defaultTickDuration),
          List(
            new Tuple2(
              now.add(tickDurationWithBuffer).toInstant,
              config201,
            ),
            new Tuple2(
              now.add(tickDurationWithBuffer.plus(Duration.ofSeconds(1))).toInstant,
              config202,
            ),
          ).asJava,
        )
      }

      // set configSchedule
      svcClient.setConfigSchedule(configSchedule)
    }

    // Each advanceRoundsByOneTick will advance the time by exactly 160 second.
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    // As the first advanceRoundsByOneTick above advances the time by exactly 160 seconds
    // and this is when the svc automaotion exercise the svc choice to advance rounds,
    // a new open mining round is created at the time when config201 is the active config.
    // After that, the second advanceRoundsByOneTick advances the time by another 160 seconds
    // another new round is created with the config202 as it was the active config at that time.
    eventually()({
      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.transferConfigUsd.maxNumInputs shouldBe config102.transferConfig.maxNumInputs
      rounds.middleOpen.data.transferConfigUsd.maxNumInputs shouldBe config201.transferConfig.maxNumInputs
      rounds.latestOpen.data.transferConfigUsd.maxNumInputs shouldBe config202.transferConfig.maxNumInputs
    })
  }

  "SVs collect SvcReward and SvReward automatically" in { implicit env =>
    initSvc()

    eventually() {
      val rounds = getSortedOpenMiningRounds(svc.remoteParticipantWithAdminToken, svcParty)
      rounds should have size 3
      svs.map { sv =>
        val coins = sv.remoteParticipant.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv.getDebugInfo().svParty)
        coins shouldBe empty
      }
    }

    // one tick - round 0 closes.
    advanceRoundsByOneTick
    val config = defaultIssuanceCurve.currentValue
    val RoundsPerYear =
      BigDecimal(365 * 24 * 60 * 60).bigDecimal.divide(BigDecimal(150.0).bigDecimal)
    val coinsToIssueToSvc = config.coinToIssuePerYear
      .multiply(
        BigDecimal(1.0).bigDecimal
          .subtract(config.appRewardPercentage)
          .subtract(config.validatorRewardPercentage)
      )
      .divide(RoundsPerYear, RoundingMode.HALF_UP)
    eventually() {
      getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 1

      // Only Sv1 get svc reward from round 0 as Sv2, Sv3 and Sv4 only joined in round 1
      inside(
        svs.head.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(svs.head.getDebugInfo().svParty)
      ) { case Seq(newCoin) =>
        newCoin.data.svc shouldBe svcParty.toProtoPrimitive
        newCoin.data.owner shouldBe svs.head.getDebugInfo().svParty.toProtoPrimitive
        newCoin.data.amount.initialAmount shouldBe coinsToIssueToSvc
          .setScale(10, RoundingMode.HALF_UP)
      }

      svs.tail.map { sv =>
        val coins = sv.remoteParticipant.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv.getDebugInfo().svParty)
        coins shouldBe empty
      }
    }

    // one tick - round 1 closes.
    advanceRoundsByOneTick
    eventually() {
      getSortedIssuingRounds(svc.remoteParticipantWithAdminToken, svcParty) should have size 2

      val eachSvGetInRound1 =
        coinsToIssueToSvc
          .divide(BigDecimal(svs.size).bigDecimal, RoundingMode.HALF_UP)
          .setScale(10, RoundingMode.HALF_UP)

      // All Svs get reward from round 1
      inside(
        svs.head.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(svs.head.getDebugInfo().svParty)
      ) { case Seq(_, newCoin) =>
        newCoin.data.svc shouldBe svcParty.toProtoPrimitive
        newCoin.data.owner shouldBe svs.head.getDebugInfo().svParty.toProtoPrimitive
        newCoin.data.amount.initialAmount shouldBe eachSvGetInRound1
      }

      svs.tail.map { sv =>
        inside(
          sv.remoteParticipantWithAdminToken.ledger_api_extensions.acs
            .filterJava(cc.coin.Coin.COMPANION)(sv.getDebugInfo().svParty)
        ) { case Seq(newCoin) =>
          newCoin.data.svc shouldBe svcParty.toProtoPrimitive
          newCoin.data.owner shouldBe sv.getDebugInfo().svParty.toProtoPrimitive
          newCoin.data.amount.initialAmount shouldBe eachSvGetInRound1
        }
      }
    }
  }

  "calculation of issuance per coin" in { implicit env =>
    initSvc()

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
    svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
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
        forAtLeast(4, entries)(
          _.message should include(
            s"created confirmation for summarizing mining round with com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary(10000.0000000000, 200.0000000000, 9800.0000000000)"
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

  "collect expired reward coupons" in { implicit env =>
    def getRewardCoupons(
        round: Contract[OpenMiningRound.ContractId, OpenMiningRound]
    ) = {
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(AppRewardCoupon.COMPANION)(
          svcParty,
          co => co.data.round.number == round.payload.round.number,
        ) ++
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorRewardCoupon.COMPANION)(
            svcParty,
            co => co.data.round.number == round.payload.round.number,
          )
    }
    initSvc()
    Seq(aliceValidator, bobValidator).foreach(_.start())
    Seq(aliceValidator, bobValidator).foreach(_.waitForInitialization())

    val round = scan.getTransferContextWithInstances(getLedgerTime).latestOpenMiningRound
    // There may be rewards left over from other tests, so we first check the
    // contract IDs of existing ones, and compare to that below
    val leftoverRewardIds = getRewardCoupons(round).view.map(_.id).toSet

    val (aliceParty, bobParty) = onboardAliceAndBob()
    aliceWallet.tap(100.0)
    bobWallet.tap(100.0)

    actAndCheck(
      "Generate some reward coupons by executing a few direct transfers", {
        p2pTransfer(aliceValidator, aliceWallet, bobWallet, bobParty, 10.0)
        p2pTransfer(aliceValidator, aliceWallet, bobWallet, bobParty, 10.0)
        p2pTransfer(bobValidator, bobWallet, aliceWallet, aliceParty, 10.0)
        p2pTransfer(bobValidator, bobWallet, aliceWallet, aliceParty, 10.0)
      },
    )(
      "Wait for all reward coupons to be created",
      _ => {
        advanceTimeByPollingInterval(sv1)
        getRewardCoupons(round)
          .filterNot(c =>
            leftoverRewardIds(c.id)
          ) should have length 8 // 4 app rewards + 4 validator
      },
    )

    actAndCheck(
      "Advance 5 ticks, to close the round",
      (1 to 5).foreach(_ => advanceRoundsByOneTick),
    )(
      "Wait for all unclaimed coupons to be archived and the closed round to be archived",
      _ => {
        advanceTimeByPollingInterval(sv1)
        getRewardCoupons(round) shouldBe empty
        scan
          .getClosedRounds()
          .filter(r => r.payload.round.number == round.payload.round.number) should be(empty)
      },
    )
  }

  "expire stale `SvOnboarding` contracts" in { implicit env =>
    clue("Initialize SVC with 3 SVs") {
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3).foreach(
        _.start()
      )
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3).foreach(
        _.waitForInitialization()
      )
      getSvcRules().data.members should have size 3
    }
    clue(
      "Add a phantom SV and stop SV3 so that SV4 can't gather enough confirmations just yet"
    ) {
      addPhantomSv()
      sv3.stop()
      getSvcRules().data.members should have size 4
      // We now need 3 confirmations to execute an action, but only sv1 and sv2 are active.
    }
    clue("SV4 starts") {
      sv4.start()
    }
    clue("An `SvOnboarding` contract is created") {
      eventually()(
        // The onboarding is requested by SV4 during SvApp init.
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) should have length 1
      )
    }
    actAndCheck("No onboarding happens for a long time", advanceTime(Duration.ofHours(25)))(
      "The `SvOnboarding` contract expires and is archived",
      _ =>
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) shouldBe empty,
    )
  }

  "expire stale `SvOnboardingConfirmed` contracts" in { implicit env =>
    clue("Initialize SVC with 3 SVs") {
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3).foreach(
        _.start()
      )
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3).foreach(
        _.waitForInitialization()
      )
      getSvcRules().data.members should have size 3
    }
    val svXParty = allocateRandomSvParty("svX")
    actAndCheck(
      "Create a new `SvOnboardingConfirmed` Contract with new party \"svX\"",
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = getSvcRules().id
          .exerciseSvcRules_ConfirmSvOnboarding(
            svXParty.toProtoPrimitive,
            "new random party",
            "create new `SvOnboardingConfirmed` contract",
          )
          .commands
          .asScala
          .toSeq,
      ),
    )(
      "SvX's `SvOnboardingConfirmed` contract is created'",
      _ =>
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(
            svcParty
          ) should have length 1,
    )
    actAndCheck(
      "No confirmation happens within 24h",
      advanceTime(Duration.ofHours(25)),
    )(
      "The `SvOnboardingConfirmed` contract expires and is archived",
      _ =>
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(svcParty) shouldBe empty,
    )
  }

  "auto-merge unclaimed rewards" in { implicit env =>
    initSvc()

    val threshold =
      10 // TODO(M3-46): base this on the actual threshold read from the svcRules config
    val numRewards = threshold + 1
    val rewardAmount = 0.1

    def getUnclaimedRewardContracts() =
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(UnclaimedReward.COMPANION)(svcParty)

    val existingUnclaimedRewards = getUnclaimedRewardContracts().length

    actAndCheck(
      s"Create as many unclaimed rewards as needed to have at least ${numRewards}", {
        val unclaimedRewards = ((existingUnclaimedRewards + 1) to numRewards).map(_ =>
          new UnclaimedReward(svcParty.toProtoPrimitive, BigDecimal(rewardAmount).bigDecimal)
        )
        if (!unclaimedRewards.isEmpty) {
          svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
            actAs = Seq(svcParty),
            optTimeout = None,
            commands = unclaimedRewards.flatMap(_.create.commands.asScala.toSeq),
          )
        }
      },
    )(
      "Wait for the unclaimed rewards to get merged automagically",
      _ => {
        advanceTimeByPollingInterval(svc)
        getUnclaimedRewardContracts().length should (be < threshold)
      },
    )
  }

  "expire stale `Confirmation` contracts" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3, sv4).foreach(
        _.start()
      )
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3, sv4).foreach(
        _.waitForInitialization()
      )
      getSvcRules().data.members should have size 4
    }
    clue(
      "Stop three SVs so that actions can't gather enough confirmations"
    ) {
      sv2.stop()
      sv3.stop()
      sv4.stop()
      getSvcRules().data.members should have size 4
      // We now need 3 confirmations to execute an action, but only sv1 is active.
    }

    clue(
      "Sync with background automation that onboards validator"
    ) {
      eventually()({
        val rounds = getSortedOpenMiningRounds(svc.remoteParticipantWithAdminToken, svcParty)
        rounds should have size 3
      })
    }

    val confirmationCid = actAndCheck(
      "Wait for one tick",
      advanceTime(tickDurationWithBuffer),
    )(
      "Find confirmation (for issuing rounds)",
      _ => {
        val contractList = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action.toValue.getConstructor() == "ARC_CoinRules")
        contractList should have length 1
        contractList(0).id
      },
    )

    val bufferDurationInSeconds = 20

    actAndCheck(
      "Wait for Confirmation TTL to elapse",
      advanceTime(
        java.time.Duration.ofSeconds(
          (SvUtil
            .defaultSvcRulesConfig()
            .actionConfirmationTimeout
            .microseconds / pow(10, 6)).toLong
            + bufferDurationInSeconds
        )
      ),
    )(
      "The Confirmation expires and is archived",
      _ => {
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action.toValue.getConstructor() == "ARC_CoinRules")
          .filter(_.id == confirmationCid) should have length 0
      },
    )

    actAndCheck(
      "Wait for one polling period",
      advanceTimeByPollingInterval(sv1),
    )(
      "Find new confirmation (for issuing rounds)",
      _ => {
        val contractList = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action.toValue.getConstructor() == "ARC_CoinRules")
        contractList should have length 1
      },
    )

  }

  "detect an inactive leader" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3, sv4).foreach(
        _.start()
      )
      Seq(svc: LocalCNNodeAppReference, scan: LocalCNNodeAppReference, sv1, sv2, sv3, sv4).foreach(
        _.waitForInitialization()
      )
      getSvcRules().data.members should have size 4
    }

    clue(
      "Wait for first three rounds to be opened"
    ) {
      eventually()({
        val rounds = getSortedOpenMiningRounds(svc.remoteParticipantWithAdminToken, svcParty)
        rounds should have size 3
      })
    }

    clue(
      "Stop the leader so we can detect its inactivity later"
    ) {
      sv1.stop()
      getSvcRules().data.members should have size 4
    }

    clue(
      "Advance time such that a new round should be opened. The leader's inactivity is detected and logged"
    ) {
      // It doesn't really matter which sv we pick
      val automationConfig = sv2.config.automation
      val effectiveTimeout = automationConfig.leaderInactiveTimeout.asJavaApproximation.plus(
        automationConfig.pollingInterval.asJavaApproximation
      )
      val bufferDuration = java.time.Duration.ofSeconds(5)

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          advanceTime(tickDurationWithBuffer)
        },
        entries => {
          forExactly(3, entries) { line =>
            line.message should include(
              "Starting check for leader inactivity"
            )
          }
        },
      )

      actAndCheck(
        "Wait for leader inactivity to be detected",
        advanceTime(effectiveTimeout.plus(bufferDuration)),
      )(
        "The inactivity is detected and logged",
        _ => {
          loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
            {},
            entries => {
              forExactly(3, entries) { line =>
                line.message should include(
                  "The leader is inactive"
                )
              }
            },
          )
        },
      )

      eventually() {
        val electionRequests = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.ElectionRequest.COMPANION)(svcParty)
        electionRequests should have length 3
        val requestsData = electionRequests.map(req => req.data)
        requestsData.foreach(data => data.ranking.asScala.head shouldBe data.requester)
        val currentLeader = getSvcRules().data.leader
        requestsData.foreach(_.ranking.asScala.last shouldBe currentLeader)
      }
    }
  }

  private def readyToAdvanceAt(rounds: OpenMiningRoundsTriplet): Instant = {
    Ordering[Instant].max(
      rounds.oldestOpen.data.targetClosesAt,
      Ordering[Instant].max(
        rounds.middleOpen.data.opensAt plus fromRelTime(
          rounds.middleOpen.data.tickDuration
        ),
        rounds.latestOpen.data.opensAt,
      ),
    )
  }

  private def fromRelTime(duration: RelTime): Duration =
    Duration.ofMillis(duration.microseconds / 1000)

  private case class OpenMiningRoundsTriplet(
      oldestOpen: OpenMiningRound.Contract,
      middleOpen: OpenMiningRound.Contract,
      latestOpen: OpenMiningRound.Contract,
  )

  private def getOpenMiningRounds()(implicit
      env: CNNodeTestConsoleEnvironment
  ): OpenMiningRoundsTriplet = {
    val rounds = getSortedOpenMiningRounds(
      svc.remoteParticipantWithAdminToken,
      svcParty,
    )
    rounds should have length 3
    OpenMiningRoundsTriplet(rounds.head, rounds(1), rounds(2))
  }

  private def advanceTimeAndCheckOpenRounds(
      toAdvanceAt: Instant
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = {
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
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = eventually() {
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

  private def toRelTime(duration: NonNegativeFiniteDuration): RelTime = new RelTime(
    duration.toInternal.toScala.toMicros
  )
}
