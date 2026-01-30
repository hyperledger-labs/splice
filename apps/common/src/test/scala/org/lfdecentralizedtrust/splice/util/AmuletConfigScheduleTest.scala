package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.splice
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class AmuletConfigScheduleTest extends AnyWordSpec with BaseTest {

  private val dummyTickDuration = NonNegativeFiniteDuration.ofMinutes(10)
  private val dummySynchronizerId = SynchronizerId.tryFromString(
    "global::122084763882fa4111e288caf832fd9e83b666acf8f167a09fc63344d2df9bcf72a7"
  )
  private def mkConfig(
      maxNumInputs: Long
  ): splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD] =
    SpliceUtil.defaultAmuletConfig(
      dummyTickDuration,
      maxNumInputs,
      dummySynchronizerId,
    )

  private val schedule1 = AmuletConfigSchedule(mkConfig(0), Seq.empty)
  private val schedule2 = AmuletConfigSchedule(
    mkConfig(0),
    Seq(
      Instant.ofEpochMilli(-1) -> mkConfig(-1),
      Instant.ofEpochMilli(2) -> mkConfig(2),
    ),
  )

  private def testCase(schedule: AmuletConfigSchedule, asOf: Int, expectedMaxNumInputs: Long) = {
    val config = schedule.getConfigAsOf(CantonTimestamp.ofEpochMilli(asOf.toLong))
    config.transferConfig.maxNumInputs shouldBe expectedMaxNumInputs
  }

  "AmuletConfigSchedule.getConfigAsOf" should {
    "work with no future-dated values" in {
      testCase(schedule1, -100, 0)
      testCase(schedule1, 0, 0)
      testCase(schedule1, 100000, 0)
    }

    "ignore future-dated values before they are effective" in {
      testCase(schedule2, -100, 0)
    }

    "pick up future-dated values as soon as they are effective" in {
      testCase(schedule2, 2, 2)
      testCase(schedule2, -1, -1)
    }

    "continue using a future-dated values until the next one becomes effective" in {
      testCase(schedule2, 0, -1)
      testCase(schedule2, 1, -1)
    }

    "continue using the last future-dated values forever" in {
      testCase(schedule2, 3, 2)
      testCase(schedule2, 1000, 2)
    }
  }

}
