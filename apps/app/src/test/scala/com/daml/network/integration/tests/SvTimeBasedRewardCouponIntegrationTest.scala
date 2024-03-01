package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.config.CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.environment.BaseLedgerConnection
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.CNNodeUtil.defaultIssuanceCurve
import com.daml.network.util.WalletTestUtil
import com.daml.network.validator.automation.ReceiveFaucetCouponTrigger
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.PartyId
import monocle.macros.syntax.lens.*

import java.math.RoundingMode
import scala.math.Ordering.Implicits.*

class SvTimeBasedRewardCouponIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with SvTimeBasedIntegrationTestUtil
    with WalletTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => {
        config
          .focus(_.svApps)
          .modify(_.map { case (name, svConfig) =>
            // TODO (#9149): Remove this transform, as they will be enabled by default
            val configWithNewIssuance =
              svConfig.focus(_.automation.useNewSvRewardIssuance).replace(true)

            // sv4 gives part of its reward to alice
            val newConfig = if (name.unwrap == "sv4") {
              val aliceParticipant =
                CNNodeConfigTransforms
                  .getParticipantIds(config.parameters.clock)("alice_validator_user")
              val aliceLedgerApiUser =
                config.validatorApps(InstanceName.tryCreate("aliceValidator")).ledgerApiUser
              val alicePartyId = PartyId
                .tryFromProtoPrimitive(
                  s"${BaseLedgerConnection.sanitizeUserIdToPartyString(aliceLedgerApiUser)}::${aliceParticipant.split("::").last}"
                )
              configWithNewIssuance
                .copy(extraBeneficiaries = Map(alicePartyId -> BigDecimal("33.33")))
            } else configWithNewIssuance

            name -> newConfig
          })
      })
      .addConfigTransforms((_, config) =>
        // makes balance changes easier to compare
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      .withTrafficTopupsDisabled

  "SVs" should {

    "receive and claim SvRewardCoupons" in { implicit env =>
      val openRounds = eventually() {
        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
        openRounds should not be empty
        openRounds
      }

      eventually() {
        val expectedSize = openRounds.size.toLong
        val sv1Coupons = sv1WalletClient.listSvRewardCoupons()
        val aliceCoupons = aliceValidatorWalletClient.listSvRewardCoupons()
        sv1Coupons should have size expectedSize
        aliceCoupons should have size expectedSize
        sv1Coupons.map(_.payload.weight) should be(
          Seq.fill(expectedSize.toInt)(BigDecimal(SvUtil.DefaultFoundingNodeWeight))
        )
        aliceCoupons.map(_.payload.weight) should be(
          Seq.fill(expectedSize.toInt)(
            BigDecimal(SvUtil.DefaultFoundingNodeWeight) * BigDecimal("0.3333")
          )
        )
        // SV4 has no wallet
      }

      // prevent other coupons from being received so that we can verify when the previous ones have been claimed.
      Seq(sv1Backend, sv4Backend).foreach { sv =>
        sv.svcAutomation
          .trigger[ReceiveSvRewardCouponTrigger]
          .pause()
          .futureValue
      }

      // advance enough rounds to claim one SvRewardCoupon
      advanceRoundsByOneTick
      advanceRoundsByOneTick
      eventually() {
        val expectedSize = (openRounds.size - 1).toLong
        sv1WalletClient.listSvRewardCoupons() should have size expectedSize
        aliceValidatorWalletClient.listSvRewardCoupons() should have size expectedSize
      }

      val config = defaultIssuanceCurve.initialValue
      val RoundsPerYear =
        BigDecimal(365 * 24 * 60 * 60).bigDecimal
          .divide(BigDecimal(defaultTickDuration.duration.toSeconds).bigDecimal)
      val coinsToIssueToSvc = config.coinToIssuePerYear
        .multiply(
          BigDecimal(1.0).bigDecimal
            .subtract(config.appRewardPercentage)
            .subtract(config.validatorRewardPercentage)
        )
        .divide(RoundsPerYear, RoundingMode.HALF_UP)

      eventually() {
        val eachSvGetInRound0 =
          coinsToIssueToSvc
            .divide(BigDecimal(svs.size).bigDecimal, RoundingMode.HALF_UP)
            .setScale(10, RoundingMode.HALF_UP)

        checkWallet(
          sv1Backend.getSvcInfo().svParty,
          sv1WalletClient,
          Seq(BigDecimal(eachSvGetInRound0) - smallAmount -> eachSvGetInRound0),
        )

        val expectedAliceAmount = eachSvGetInRound0.multiply(new java.math.BigDecimal("0.3333"))
        checkWallet(
          aliceValidatorBackend.getValidatorPartyId(),
          aliceValidatorWalletClient,
          Seq(BigDecimal(expectedAliceAmount) - smallAmount -> expectedAliceAmount),
        )
      }
    }
  }

}
