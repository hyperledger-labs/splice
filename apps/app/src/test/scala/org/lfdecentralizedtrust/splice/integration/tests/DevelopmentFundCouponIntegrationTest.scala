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
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_15
class DevelopmentFundCouponIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil
    with WalletTestUtil {

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
            unclaimedDevelopmentFundCouponsThreshold = 3
          )
        )(config)
      )

  "UnclaimedDevelopmentFundCoupons are merged" in { implicit env =>
    actAndCheck(
      "Advance 3 rounds", {
        Range(0, 3).foreach(_ => advanceRoundsByOneTickViaAutomation())
      },
    )(
      "3 UnclaimedDevelopmentFundCoupons are created and the trigger does not merge the coupons",
      _ =>
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedDevelopmentFundCoupon.COMPANION)(dsoParty)
          .size shouldBe 3,
    )

    actAndCheck(
      "Advance 1 round to create one more UnclaimedDevelopmentFundCoupon",
      advanceRoundsByOneTickViaAutomation(),
    )(
      "MergeUnclaimedDevelopmentFundCouponsTrigger detects that the threshold has been reached and " +
        "merges the available UnclaimedDevelopmentFundCoupons",
      _ => {
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedDevelopmentFundCoupon.COMPANION)(dsoParty)
          .size shouldBe 1
      },
    )
  }
}
