package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.Identifier
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedDevelopmentFundCoupon
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}

import java.time.Duration

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_21
class DevelopmentFundCouponIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil
    with WalletTestUtil {

  private val threshold = 3

  override protected lazy val sanityChecksIgnoredRootCreates: Seq[Identifier] = Seq(
    UnclaimedDevelopmentFundCoupon.TEMPLATE_ID_WITH_PACKAGE_ID
  )

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
    val (_, couponAmount) = actAndCheck(
      "Advance 5 rounds", {
        Range(0, 5).foreach(_ => advanceRoundsByOneTickViaAutomation())
      },
    )(
      "5 UnclaimedDevelopmentFundCoupons are created, and the trigger does not merge the coupons, " +
        "as it only acts when the number of coupons is ≥ 2 × threshold",
      _ => {
        val coupons = sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedDevelopmentFundCoupon.COMPANION)(dsoParty)
        coupons.size shouldBe 5
        coupons.head.data.amount
      },
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
          .map(_.data.amount)
          .sorted shouldBe Seq(
          couponAmount,
          couponAmount,
          couponAmount,
          couponAmount.multiply(new java.math.BigDecimal(3)),
        )
      },
    )

    actAndCheck(
      "Advance two rounds to create two more UnclaimedDevelopmentFundCoupon, " +
        "reaching 2 × threshold coupons and triggering a second merge",
      Range(0, 2).foreach(_ => advanceRoundsByOneTickViaAutomation()),
    )(
      "The MergeUnclaimedDevelopmentFundCouponsTrigger merges the `threshold` smallest coupons",
      _ => {
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedDevelopmentFundCoupon.COMPANION)(dsoParty)
          .map(_.data.amount)
          .sorted shouldBe Seq(
          couponAmount,
          couponAmount,
          couponAmount.multiply(new java.math.BigDecimal(3)),
          couponAmount.multiply(new java.math.BigDecimal(3)),
        )
      },
    )
  }

  "DevelopmentFundCoupons management flow" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val unclaimedDevelopmentFundCouponsToMint = Seq(10.0, 10.0, 30.0, 30.0)
    val unclaimedDevelopmentFundCouponTotal = unclaimedDevelopmentFundCouponsToMint.sum
    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val fundManager = aliceParty
    val beneficiary = bobParty
    val developmentFundCouponAmount = 40.0
    val expiresAt = CantonTimestamp.now().plus(Duration.ofDays(1))
    val reason = "Bob has contributed to the Daml repo"

    val unclaimedDevelopmentFundCouponContractIds =
      clue("Mint some unclaimed development fund coupons") {
        unclaimedDevelopmentFundCouponsToMint.foreach { amount =>
          createUnclaimedDevelopmentFundCoupon(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserId,
            amount,
          )
        }
        val unclaimedDevelopmentFundCouponContracts =
          sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(UnclaimedDevelopmentFundCoupon.COMPANION)(dsoParty)
        unclaimedDevelopmentFundCouponContracts
          .map(co => BigDecimal(co.data.amount))
          .sum shouldBe unclaimedDevelopmentFundCouponTotal
        unclaimedDevelopmentFundCouponContracts.map(_.id)
      }

    actAndCheck(
      "allocate one development fund coupon", {
        aliceWalletClient.allocateDevelopmentFundCoupon(
          unclaimedDevelopmentFundCouponContractIds,
          beneficiary,
          developmentFundCouponAmount,
          expiresAt,
          reason,
          fundManager,
        )
      },
    )(
      "One development fund coupon is allocated and the total of unclaimed development fund coupons has decreased",
      _ => {
        val activeDevelopmentFundCoupons =
          aliceWalletClient.listActiveDevelopmentFundCoupons().map(_.payload)
        activeDevelopmentFundCoupons.length shouldBe 1
        val bobDevelopmentFundCoupon = activeDevelopmentFundCoupons.head
        bobDevelopmentFundCoupon.amount shouldBe developmentFundCouponAmount
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(UnclaimedDevelopmentFundCoupon.COMPANION)(dsoParty)
          .map(co => BigDecimal(co.data.amount))
          .sum shouldBe (unclaimedDevelopmentFundCouponTotal - developmentFundCouponAmount)
      },
    )
  }
}
