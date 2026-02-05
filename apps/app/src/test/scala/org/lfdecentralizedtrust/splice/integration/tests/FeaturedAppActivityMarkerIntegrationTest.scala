package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.featuredapprightv1
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
  FeaturedAppActivityMarkerTrigger,
}
import org.lfdecentralizedtrust.splice.util.*

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class FeaturedAppActivityMarkerIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      // Using 4Svs so that we see whether they manage to jointly complete all work
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(
            initialFeaturedAppActivityMarkerAmount = Some(1.0),
            initialAmuletPrice = 1.0,
          )
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
            .withPausedTrigger[FeaturedAppActivityMarkerTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(
            delegatelessAutomationFeaturedAppActivityMarkerCatchupThreshold = 10,
            delegatelessAutomationFeaturedAppActivityMarkerBatchSize = 2,
          )
        )(config)
      )

  "Reward collection should work for featured app activity markers" in { implicit env =>
    val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val charlie = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
    val aliceFeaturedAppRightCid = eventuallySucceeds() {
      aliceWalletClient
        .selfGrantFeaturedAppRight()
        .toInterface(featuredapprightv1.FeaturedAppRight.INTERFACE)
    }
    val bobFeaturedAppRightCid = eventuallySucceeds() {
      bobWalletClient
        .selfGrantFeaturedAppRight()
        .toInterface(featuredapprightv1.FeaturedAppRight.INTERFACE)
    }

    val markerMultiplier = 10

    actAndCheck(timeUntilSuccess = 60.seconds)(
      "Create activity markers", {
        for (i <- 1 to markerMultiplier) {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              Seq(alice),
              commands = aliceFeaturedAppRightCid
                .exerciseFeaturedAppRight_CreateActivityMarker(
                  Seq(
                    new featuredapprightv1.AppRewardBeneficiary(
                      alice.toProtoPrimitive,
                      BigDecimal(0.2).bigDecimal,
                    ),
                    new featuredapprightv1.AppRewardBeneficiary(
                      charlie.toProtoPrimitive,
                      BigDecimal(0.8).bigDecimal,
                    ),
                  ).asJava
                )
                .commands
                .asScala
                .toSeq,
            )
          bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              Seq(bob),
              commands = bobFeaturedAppRightCid
                .exerciseFeaturedAppRight_CreateActivityMarker(
                  Seq(
                    new featuredapprightv1.AppRewardBeneficiary(
                      bob.toProtoPrimitive,
                      BigDecimal(1.0).bigDecimal,
                    )
                  ).asJava
                )
                .commands
                .asScala
                .toSeq,
            )
        }
        // unpause all activity marker triggers here, so they can start to get to work
        env.svs.local.foreach(
          _.dsoDelegateBasedAutomation.trigger[FeaturedAppActivityMarkerTrigger].resume()
        )
      },
    )(
      "Activity markers are converted to reward coupons",
      _ => {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs.filterJava(
          amulet.FeaturedAppActivityMarker.COMPANION
        )(dsoParty, _ => true) should have size 0
        inside(
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(
              amulet.AppRewardCoupon.COMPANION
            )(alice, c => c.data.provider == alice.toProtoPrimitive)
        ) { aliceCoupons =>
          // The actual number of coupons is non-deterministic due to the random sampling and
          // the batches only creating one coupon per beneficiary. There are at least two, as there are two beneficiaries.
          aliceCoupons.size should be >= 2
          val totalWeight: BigDecimal = aliceCoupons.map(co => BigDecimal(co.data.amount)).sum
          totalWeight shouldBe BigDecimal(markerMultiplier)
        }
        inside(
          bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.filterJava(
            amulet.AppRewardCoupon.COMPANION
          )(bob, c => c.data.provider == bob.toProtoPrimitive)
        ) { bobCoupons =>
          bobCoupons.size should be >= 1
          val totalWeight: BigDecimal = bobCoupons.map(co => BigDecimal(co.data.amount)).sum
          totalWeight shouldBe BigDecimal(markerMultiplier)
        }
      },
    )
  }
}
