package com.daml.network.sv.util

import com.daml.network.sv.config.BeneficiaryConfig
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.logging.{SuppressionRule, TracedLogger}
import com.digitalasset.canton.topology.PartyId
import org.scalatest.wordspec.AsyncWordSpec
import org.slf4j.event.Level
import scala.language.implicitConversions

class WeightDistributionForSvTest extends AsyncWordSpec with BaseTest {

  private implicit def castNonNegativeLong(l: Long): NonNegativeLong =
    NonNegativeLong.tryCreate(l)

  "weightDistributionForSv" should {

    val svWeight = 10_000L
    def mkPartyId(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")
    val svParty = mkPartyId("sv1")
    val validator1 = mkPartyId("v1")
    val validator2 = mkPartyId("v2")
    implicit val loggerImpl: TracedLogger = logger

    "give all weight to sv if there are no beneficiaries" in {
      val result = SvUtil.weightDistributionForSv(svWeight, Seq.empty, svParty)
      result should be(Map(svParty -> svWeight))
    }

    "weight is capped at remainder" in {
      loggerFactory.assertLogs(SuppressionRule.Level(Level.WARN))(
        {
          val result =
            SvUtil.weightDistributionForSv(
              svWeight,
              Seq(BeneficiaryConfig(validator1, 3333L), BeneficiaryConfig(validator2, 6668L)),
              svParty,
            )
          result should be(
            Map(validator1 -> 3333L, validator2 -> 6667L)
          )
        },
        _.warningMessage should be(
          s"Beneficiary weight 6668 for $validator2 is greater than the remainder 6667, capping weight to remainder"
        ),
      )
    }

    "remainder goes to SV" in {
      val result =
        SvUtil.weightDistributionForSv(
          svWeight,
          Seq(BeneficiaryConfig(validator1, 3333L), BeneficiaryConfig(validator2, 6000L)),
          svParty,
        )
      result should be(
        Map(validator1 -> 3333L, validator2 -> 6000L, svParty -> 667L)
      )
    }

    "party can be specified multiple times" in {
      val result =
        SvUtil.weightDistributionForSv(
          svWeight,
          Seq(BeneficiaryConfig(validator1, 3333L), BeneficiaryConfig(validator1, 6667L)),
          svParty,
        )
      result should be(
        Map(validator1 -> 10000L)
      )
    }

    "extra SV weights are dropped" in {
      loggerFactory.assertLogs(SuppressionRule.Level(Level.INFO))(
        {
          val result =
            SvUtil.weightDistributionForSv(
              svWeight,
              Seq(BeneficiaryConfig(validator1, 10000L), BeneficiaryConfig(validator2, 6668L)),
              svParty,
            )
          result should be(
            Map(validator1 -> 10000L)
          )
        },
        _.infoMessage should be(
          s"Total SV weight 10000 does not cover the following beneficiaries: List(BeneficiaryConfig($validator2,6668))"
        ),
      )
    }

  }

}
