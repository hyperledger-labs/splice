package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.{ConfigScheduleUtil, JavaDecodeUtil as DecodeUtil}
import com.digitalasset.canton.time.EnrichedDurations.*

import java.time.Duration as JavaDuration
import scala.jdk.CollectionConverters.*

class SvTimeBasedRoundMgmtIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment
    with ConfigScheduleUtil {

  "round management" in { implicit env =>
    initDso()

    // Sync with background automation that onboards validator.
    eventually()({
      val rounds = getSortedOpenMiningRounds(sv1Backend.participantClientWithAdminToken, dsoParty)
      rounds should have size 3
    })

    // one tick - round 0 closes.
    advanceRoundsByOneTick
    eventually()(
      getSortedIssuingRounds(
        sv1Backend.participantClientWithAdminToken,
        dsoParty,
      ) should have size 1
    )
    // next tick - round 1 closes.
    advanceRoundsByOneTick
    eventually()(
      getSortedIssuingRounds(
        sv1Backend.participantClientWithAdminToken,
        dsoParty,
      ) should have size 2
    )
    // next tick - round 2 closes.
    advanceRoundsByOneTick
    eventually()(
      getSortedIssuingRounds(
        sv1Backend.participantClientWithAdminToken,
        dsoParty,
      ) should have size 3
    )

    val offsetBefore = sv1Backend.participantClientWithAdminToken.ledger_api.state.end()
    // next tick - issuing round 0 can be closed
    // not using `advanceRoundsByOneTick` because this interferes with checking the state of the ClosedMiningRounds
    advanceTime(tickDurationWithBuffer)
    eventually() {
      // Check for closing mining round in transactions instead of acs
      // to guard against automation archiving it concurrently.
      val ledgerEnd = sv1Backend.participantClientWithAdminToken.ledger_api.state.end()
      val transactions =
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.transactions
          .treesJava(
            Set(dsoParty),
            completeAfter = Int.MaxValue,
            beginOffset = offsetBefore,
            endOffset = Some(ledgerEnd),
          )
      val rounds =
        transactions.flatMap(
          DecodeUtil.decodeAllCreatedTree(splice.round.ClosedMiningRound.COMPANION)(_)
        )
      rounds should have size 1
    }
    eventually()( // .. hence even though a fourth issuing round is created, we end up with 3 active issuing rounds eventually.
      getSortedIssuingRounds(
        sv1Backend.participantClientWithAdminToken,
        dsoParty,
      ) should have size 3
    )

    clue("Wait until the closed round is archived") {
      eventually()(
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(splice.round.ClosedMiningRound.COMPANION)(dsoParty) should have size 0
      )
    }

  }

  "round management with scheduled config change of doubled tickDuration" in { implicit env =>
    initDsoWithSv1Only()
    val currentConfigSchedule = sv1ScanBackend.getAmuletRules().contract.payload.configSchedule

    val doubledTickDuration = defaultTickDuration * 2

    setFutureConfigSchedule(
      createConfigSchedule(
        currentConfigSchedule,
        (
          defaultTickDuration.asJava,
          mkUpdatedAmuletConfig(currentConfigSchedule, doubledTickDuration),
        ),
      )
    )
    advanceRoundsByOneTick

    // latest OpenMiningRound was created with doubled tick duration.
    eventually()({
      val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.tickDuration shouldBe SvUtil.toRelTime(defaultTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe SvUtil.toRelTime(defaultTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe SvUtil.toRelTime(doubledTickDuration)

      rounds.latestOpen.data.opensAt shouldBe (now + doubledTickDuration.toInternal).toInstant
      rounds.latestOpen.data.targetClosesAt shouldBe (
        now + doubledTickDuration.toInternal + doubledTickDuration.toInternal + doubledTickDuration.toInternal
      ).toInstant
    })

    clue("advance to OpenMiningRound 4") {
      // First IssuingRounds is active
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.asJava
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
          0L -> defaultTickDuration.asJava,
          1L -> defaultTickDuration.asJava,
        )
      )

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.tickDuration shouldBe SvUtil.toRelTime(defaultTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe SvUtil.toRelTime(doubledTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe SvUtil.toRelTime(doubledTickDuration)

      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt

      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 6") {
      assertTickDurationOfIssuingRound(
        Map(
          1L -> defaultTickDuration.asJava,
          2L -> defaultTickDuration.asJava,
        )
      )

      val rounds = getOpenMiningRounds()
      // all active open mining rounds are created with doubled tick
      rounds.oldestOpen.data.tickDuration shouldBe SvUtil.toRelTime(doubledTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe SvUtil.toRelTime(doubledTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe SvUtil.toRelTime(doubledTickDuration)

      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 7") {
      assertTickDurationOfIssuingRound(
        Map(
          2L -> defaultTickDuration.asJava,
          3L -> doubledTickDuration.asJava,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }
  }

  "round management with scheduled config change of reduced tickDuration" in { implicit env =>
    initDsoWithSv1Only()
    val currentConfigSchedule = sv1ScanBackend.getAmuletRules().contract.payload.configSchedule

    val reducedTickDuration = defaultTickDuration * 0.5
    val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
    sv1ScanBackend.getAmuletConfigAsOf(now).decentralizedSynchronizer.activeSynchronizer
    setFutureConfigSchedule(
      createConfigSchedule(
        currentConfigSchedule,
        (
          defaultTickDuration.asJava,
          mkUpdatedAmuletConfig(currentConfigSchedule, reducedTickDuration),
        ),
      )
    )
    advanceRoundsByOneTick

    // latest OpenMiningRound was created with reduced tick duration.
    eventually()({
      val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.tickDuration shouldBe SvUtil.toRelTime(defaultTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe SvUtil.toRelTime(defaultTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe SvUtil.toRelTime(reducedTickDuration)

      rounds.latestOpen.data.opensAt shouldBe (now + reducedTickDuration.toInternal).toInstant
      rounds.latestOpen.data.targetClosesAt shouldBe (
        now + reducedTickDuration.toInternal + reducedTickDuration.toInternal + reducedTickDuration.toInternal
      ).toInstant
    })

    clue("advance to OpenMiningRound 4") {
      // First IssuingRounds is active
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.asJava
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      // As tick duration of latestOpen is reduced,
      // latestOpen.opensAt is now before middleOpen + tickDuration
      // Instead of latestOpen.opensAt middleOpen + tickDuration becomes the time when it is ready to advance rounds
      expectedAdvanceRoundAt shouldBe (
        rounds.middleOpen.data.opensAt plus SvUtil.fromRelTime(
          rounds.middleOpen.data.tickDuration
        )
      )
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }

    clue("advance to OpenMiningRound 5") {
      assertTickDurationOfIssuingRound(
        Map(
          0L -> defaultTickDuration.asJava,
          1L -> defaultTickDuration.asJava,
        )
      )

      val rounds = getOpenMiningRounds()
      rounds.oldestOpen.data.tickDuration shouldBe SvUtil.toRelTime(defaultTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe SvUtil.toRelTime(reducedTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe SvUtil.toRelTime(reducedTickDuration)

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
          0L -> defaultTickDuration.asJava,
          1L -> defaultTickDuration.asJava,
          2L -> defaultTickDuration.asJava,
        )
      )

      val rounds = getOpenMiningRounds()
      // all active open mining rounds are created with reduced tick
      rounds.oldestOpen.data.tickDuration shouldBe SvUtil.toRelTime(reducedTickDuration)
      rounds.middleOpen.data.tickDuration shouldBe SvUtil.toRelTime(reducedTickDuration)
      rounds.latestOpen.data.tickDuration shouldBe SvUtil.toRelTime(reducedTickDuration)

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
          0L -> defaultTickDuration.asJava,
          1L -> defaultTickDuration.asJava,
          2L -> defaultTickDuration.asJava,
          3L -> reducedTickDuration.asJava,
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
          1L -> defaultTickDuration.asJava,
          2L -> defaultTickDuration.asJava,
          3L -> reducedTickDuration.asJava,
          4L -> reducedTickDuration.asJava,
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
          1L -> defaultTickDuration.asJava,
          2L -> defaultTickDuration.asJava,
          3L -> reducedTickDuration.asJava,
          4L -> reducedTickDuration.asJava,
          5L -> reducedTickDuration.asJava,
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
          2L -> defaultTickDuration.asJava,
          4L -> reducedTickDuration.asJava,
          5L -> reducedTickDuration.asJava,
          6L -> reducedTickDuration.asJava,
        )
      )

      val rounds = getOpenMiningRounds()
      val expectedAdvanceRoundAt = readyToAdvanceAt(rounds)
      expectedAdvanceRoundAt shouldBe rounds.latestOpen.data.opensAt
      advanceTimeAndCheckOpenRounds(expectedAdvanceRoundAt)
    }
  }

  "round management with very tightly scheduled config" in { implicit env =>
    initDsoWithSv1Only()
    val currentConfigSchedule = sv1ScanBackend.getAmuletRules().contract.payload.configSchedule

    val config101 = mkUpdatedAmuletConfig(currentConfigSchedule, defaultTickDuration, 101)
    val config102 = mkUpdatedAmuletConfig(currentConfigSchedule, defaultTickDuration, 102)

    setFutureConfigSchedule(
      createConfigSchedule(
        currentConfigSchedule,
        (JavaDuration.ofSeconds(150), config101),
        (JavaDuration.ofSeconds(151), config102),
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

    val config201 = mkUpdatedAmuletConfig(currentConfigSchedule, defaultTickDuration, 201)
    val config202 = mkUpdatedAmuletConfig(currentConfigSchedule, defaultTickDuration, 202)

    {
      val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
      val configSchedule = {
        new splice.schedule.Schedule(
          mkUpdatedAmuletConfig(currentConfigSchedule, defaultTickDuration),
          List(
            new Tuple2(
              now.add(tickDurationWithBuffer).toInstant,
              config201,
            ),
            new Tuple2(
              now.add(tickDurationWithBuffer.plus(JavaDuration.ofSeconds(1))).toInstant,
              config202,
            ),
          ).asJava,
        )
      }

      setFutureConfigSchedule(configSchedule)
    }

    // Each advanceRoundsByOneTick will advance the time by exactly 160 second.
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    // As the first advanceRoundsByOneTick above advances the time by exactly 160 seconds
    // and this is when the dso automaotion exercise the dso choice to advance rounds,
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
}
