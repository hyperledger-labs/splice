package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec

class RewardComputationTriggerTest extends AnyWordSpec with BaseTest {

  import RewardComputationTrigger.nextRound
  import RewardComputationTriggerTest.*

  "RewardComputationTrigger.nextRound" should {

    "return None when no complete activity data exists" in {
      nextRound(noRound, noRound, noRound) shouldBe None
      nextRound(round5, noRound, noRound) shouldBe None
      nextRound(noRound, round10, noRound) shouldBe None
    }

    "return the earliest complete round when nothing has been computed" in {
      nextRound(round3, round10, noRound) shouldBe Some(3L)
    }

    "return the round after the latest computed" in {
      nextRound(round3, round10, round5) shouldBe Some(6L)
    }

    "return earliest complete when it is ahead of latest computed" in {
      nextRound(round8, round10, round5) shouldBe Some(8L)
    }

    "return None when all complete rounds have been computed" in {
      nextRound(round3, round10, round10) shouldBe None
      nextRound(round3, round10, round15) shouldBe None
    }

    "return round when earliest equals latest complete" in {
      nextRound(round5, round5, noRound) shouldBe Some(5L)
      nextRound(round5, round5, round4) shouldBe Some(5L)
      nextRound(round5, round5, round5) shouldBe None
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
