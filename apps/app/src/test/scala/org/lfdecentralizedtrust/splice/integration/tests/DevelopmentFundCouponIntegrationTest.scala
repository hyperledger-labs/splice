package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedDevelopmentFundCoupon
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_21
class DevelopmentFundCouponIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil
    with WalletTestUtil {

  private val threshold = 3

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(
            unclaimedDevelopmentFundCouponsThreshold = threshold
          )
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.withDevelopmentFundPercentage(0.05)(config)
      )

  "UnclaimedDevelopmentFundCoupons are merged" in { implicit env =>
    actAndCheck(
      "Advance 5 rounds", {
        Range(0, 5).foreach(_ => advanceRoundsByOneTickViaAutomation())
      },
    )(
      "5 UnclaimedDevelopmentFundCoupons are created, and the trigger does not merge the coupons, " +
        "as it only acts when the number of coupons is ≥ 2 × threshold",
      _ =>
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedDevelopmentFundCoupon.COMPANION)(dsoParty)
          .size shouldBe 5,
    )

    actAndCheck(
      "Advance one round to create one more UnclaimedDevelopmentFundCoupon, reaching 2 × threshold coupons",
      advanceRoundsByOneTickViaAutomation(),
    )(
      "The MergeUnclaimedDevelopmentFundCouponsTrigger is triggered and merges the smallest three coupons (threshold), " +
        "while keeping the remaining coupons unchanged.",
      _ => {
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedDevelopmentFundCoupon.COMPANION)(dsoParty)
          .size shouldBe threshold + 1
      },
    )
  }
}
