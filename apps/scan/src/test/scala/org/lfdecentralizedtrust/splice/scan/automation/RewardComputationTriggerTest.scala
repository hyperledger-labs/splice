package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.scan.rewards.RewardComputationInputs
import org.lfdecentralizedtrust.splice.scan.rewards.RewardComputationInputs.{fromBigDecimal as n}
import org.scalatest.wordspec.AnyWordSpec

class RewardComputationTriggerTest extends AnyWordSpec with BaseTest {

  import RewardComputationTrigger.nextTask
  import RewardComputationTriggerTest.*

  private def next(
      earliest: Option[Long],
      latest: Option[Long],
      computed: Option[Long],
  ) = nextTask(earliest, latest, computed, testBatchSize, testInputs)

  "RewardComputationTrigger.nextTask" should {

    "return empty when no complete activity data exists" in {
      next(noRound, noRound, noRound) shouldBe empty
      next(round5, noRound, noRound) shouldBe empty
      next(noRound, round10, noRound) shouldBe empty
    }

    "return the earliest complete round when nothing has been computed" in {
      next(round3, round10, noRound) shouldBe Seq(task(3))
    }

    "return the round after the latest computed" in {
      next(round3, round10, round5) shouldBe Seq(task(6))
    }

    "return earliest complete when it is ahead of latest computed" in {
      next(round8, round10, round5) shouldBe Seq(task(8))
    }

    "return empty when all complete rounds have been computed" in {
      next(round3, round10, round10) shouldBe empty
      next(round3, round10, round15) shouldBe empty
    }

    "return single task when earliest equals latest complete" in {
      next(round5, round5, noRound) shouldBe Seq(task(5))
      next(round5, round5, round4) shouldBe Seq(task(5))
      next(round5, round5, round5) shouldBe empty
    }
  }
}

object RewardComputationTriggerTest {
  val noRound: Option[Long] = None
  val round3: Option[Long] = Some(3)
  val round4: Option[Long] = Some(4)
  val round5: Option[Long] = Some(5)
  val round8: Option[Long] = Some(8)
  val round10: Option[Long] = Some(10)
  val round15: Option[Long] = Some(15)

  // Simple fake values — this test only checks round selection logic,
  // not reward computation correctness.
  val testBatchSize: Int = 10
  val testInputs: RewardComputationInputs = RewardComputationInputs(
    amuletToIssuePerYear = n(BigDecimal("1000")),
    appRewardPercentage = n(BigDecimal("0.5")),
    featuredAppRewardCap = n(BigDecimal("100")),
    unfeaturedAppRewardCap = n(BigDecimal("0.6")),
    developmentFundPercentage = n(BigDecimal("0")),
    tickDurationMicros = 600L * 1000000L,
    amuletPrice = n(BigDecimal("1")),
    trafficPrice = n(BigDecimal("1")),
    appRewardCouponThreshold = n(BigDecimal("0.5")),
  )

  def task(roundNumber: Long): RewardComputationTrigger.Task =
    RewardComputationTrigger.Task(roundNumber, testBatchSize, testInputs)
}
