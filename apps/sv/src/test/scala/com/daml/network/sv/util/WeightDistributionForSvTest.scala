package com.daml.network.sv.util

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.logging.{SuppressionRule, TracedLogger}
import com.digitalasset.canton.topology.PartyId
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.event.Level

class WeightDistributionForSvTest extends AsyncWordSpec with BaseTest {

  "weightDistributionForSv" should {

    val memberWeight = 10_000L
    def mkPartyId(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")
    val svParty = mkPartyId("sv1")
    val validator1 = mkPartyId("v1")
    val validator2 = mkPartyId("v2")
    implicit val loggerImpl: TracedLogger = logger

    "give all weight to sv if there are no beneficiaries" in {
      val result = SvUtil.weightDistributionForSv(memberWeight, Map.empty, svParty)
      result should be(Map(svParty -> memberWeight))
    }

    "give all weight to SV if the weight distribution is too big" in {
      loggerFactory.assertLogs(SuppressionRule.Level(Level.ERROR))(
        {
          val result =
            SvUtil.weightDistributionForSv(
              memberWeight,
              Map(validator1 -> BigDecimal("33.33"), validator2 -> BigDecimal("66.68")),
              svParty,
            )
          result should be(
            Map(svParty -> memberWeight)
          )
        },
        _.errorMessage should be(
          s"Total weight of extra beneficiaries exceeds the member's svRewardWeightBps: $memberWeight. " +
            s"Amount will be attributed solely to the SV."
        ),
      )
    }

    "give leftovers to SV" in {
      val result = SvUtil.weightDistributionForSv(
        memberWeight,
        Map(validator1 -> BigDecimal("33.33"), validator2 -> BigDecimal("66.66")),
        svParty,
      )
      result should be(
        Map(
          validator1 -> BigDecimal("3333"),
          validator2 -> BigDecimal("6666"),
          svParty -> BigDecimal("1"),
        )
      )
    }

    "give nothing to the SV if there are no leftovers" in {
      val result = SvUtil.weightDistributionForSv(
        memberWeight,
        Map(validator1 -> BigDecimal("33.34"), validator2 -> BigDecimal("66.66")),
        svParty,
      )
      result should be(
        Map(
          validator1 -> BigDecimal("3334"),
          validator2 -> BigDecimal("6666"),
        )
      )
    }

  }

}
