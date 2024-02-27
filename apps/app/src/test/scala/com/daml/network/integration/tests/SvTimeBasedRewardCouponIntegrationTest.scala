package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import com.daml.network.util.CNNodeUtil.defaultIssuanceCurve
import com.daml.network.util.WalletTestUtil
import com.daml.network.validator.automation.ReceiveFaucetCouponTrigger
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
      // TODO (#9149): Remove this transform, as they will be enabled by default
      .addConfigTransform((_, config) =>
        config
          .focus(_.svApps)
          .modify(_.map { case (name, svConfig) =>
            name -> svConfig.focus(_.automation.useNewSvRewardIssuance).replace(true)
          })
      )
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
        sv1WalletClient.listSvRewardCoupons() should have size openRounds.size.toLong
      }

      // prevent other coupons from being received so that we can verify when the previous ones have been claimed.
      sv1Backend.svcAutomation
        .trigger[ReceiveSvRewardCouponTrigger]
        .pause()
        .futureValue

      // advance enough rounds to claim one SvRewardCoupon
      advanceRoundsByOneTick
      advanceRoundsByOneTick
      eventually() {
        sv1WalletClient.listSvRewardCoupons() should have size (openRounds.size - 1).toLong
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

        // TODO (#10245): check with other beneficiaries
        checkWallet(
          sv1Backend.getSvcInfo().svParty,
          sv1WalletClient,
          Seq(BigDecimal(eachSvGetInRound0) - smallAmount -> eachSvGetInRound0),
        )
      }
    }
  }

}
