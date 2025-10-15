package org.lfdecentralizedtrust.splice.integration.tests

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
}
import org.lfdecentralizedtrust.splice.util.*
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.featuredapprightv1
import org.lfdecentralizedtrust.splice.wallet.store.TransferTxLogEntry
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
      .simpleTopology1Sv(this.getClass.getSimpleName)
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
        )(config)
      )

  "Reward collection should work for featured app activity markers" in { implicit env =>
    val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val charlie = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
    val aliceFeaturedAppRightCid = aliceWalletClient
      .selfGrantFeaturedAppRight()
      .toInterface(featuredapprightv1.FeaturedAppRight.INTERFACE)
    val bobFeaturedAppRightCid = bobWalletClient
      .selfGrantFeaturedAppRight()
      .toInterface(featuredapprightv1.FeaturedAppRight.INTERFACE)

    actAndCheck(
      "Create activity markers", {
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
      },
    )(
      "Activity markers are converted to reward coupons",
      _ => {
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.filterJava(
          amulet.AppRewardCoupon.COMPANION
        )(alice, c => c.data.provider == alice.toProtoPrimitive) should have size 2
        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.filterJava(
          amulet.AppRewardCoupon.COMPANION
        )(bob, c => c.data.provider == bob.toProtoPrimitive) should have size 1
      },
    )

    // Advance three times so the round the coupons are assigned to is issuing
    actAndCheck(
      "Advance until reward coupon round is issuing", {
        advanceRoundsByOneTickViaAutomation()
        advanceRoundsByOneTickViaAutomation()
        advanceRoundsByOneTickViaAutomation()
      },
    )(
      "Rewards are minted",
      _ => {
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.filterJava(
          amulet.AppRewardCoupon.COMPANION
        )(alice, c => c.data.provider == alice.toProtoPrimitive) shouldBe empty
        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.filterJava(
          amulet.AppRewardCoupon.COMPANION
        )(bob, c => c.data.provider == bob.toProtoPrimitive) shouldBe empty

        // Check that tx logs work as expected, the exact amounts are just based on testing, the important part is alice sees only the minting for the reward she is a beneficiary on
        // and not the one for charlie even though she is the provider and the maounts of alice and charlie add up to bob.
        inside(
          aliceWalletClient.listTransactions(beginAfterId = None, pageSize = 1000).loneElement
        ) { case transfer: TransferTxLogEntry =>
          transfer.appRewardsUsed should beAround(9.0)
        }

        inside(
          charlieWalletClient.listTransactions(beginAfterId = None, pageSize = 1000).loneElement
        ) { case transfer: TransferTxLogEntry =>
          transfer.appRewardsUsed should beAround(38.0)
        }

        inside(bobWalletClient.listTransactions(beginAfterId = None, pageSize = 1000).loneElement) {
          case transfer: TransferTxLogEntry =>
            transfer.appRewardsUsed should beAround(47.0)
        }
      },
    )
  }
}
