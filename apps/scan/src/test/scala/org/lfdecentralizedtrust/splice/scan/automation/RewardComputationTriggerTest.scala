package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec

class RewardComputationTriggerTest extends AnyWordSpec with BaseTest {

  import RewardComputationTrigger.{Task, nextTask}
  import RewardComputationTriggerTest.*

  "RewardComputationTrigger.nextTask" should {

    "return empty when no complete activity data exists" in {
      nextTask(noRound, noRound, noRound) shouldBe empty
      nextTask(round5, noRound, noRound) shouldBe empty
      nextTask(noRound, round10, noRound) shouldBe empty
    }

    "return the earliest complete round when nothing has been computed" in {
      nextTask(round3, round10, noRound) shouldBe Seq(Task(3))
    }

    "return the round after the latest computed" in {
      nextTask(round3, round10, round5) shouldBe Seq(Task(6))
    }

    "return earliest complete when it is ahead of latest computed" in {
      nextTask(round8, round10, round5) shouldBe Seq(Task(8))
    }

    "return empty when all complete rounds have been computed" in {
      nextTask(round3, round10, round10) shouldBe empty
      nextTask(round3, round10, round15) shouldBe empty
    }

    "return single task when earliest equals latest complete" in {
      nextTask(round5, round5, noRound) shouldBe Seq(Task(5))
      nextTask(round5, round5, round4) shouldBe Seq(Task(5))
      nextTask(round5, round5, round5) shouldBe empty
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
}
