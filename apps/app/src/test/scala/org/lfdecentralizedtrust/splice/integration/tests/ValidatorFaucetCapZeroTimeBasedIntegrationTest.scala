package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.IssuanceConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  SpliceUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.schedule.Schedule
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}

import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class ValidatorFaucetCapZeroTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with AmuletConfigUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )

  /** Copy an IssuanceConfig with optValidatorFaucetCap set to 0. */
  private def withZeroFaucetCap(ic: IssuanceConfig): IssuanceConfig =
    new IssuanceConfig(
      ic.amuletToIssuePerYear,
      ic.validatorRewardPercentage,
      ic.appRewardPercentage,
      ic.validatorRewardCap,
      ic.featuredAppRewardCap,
      ic.unfeaturedAppRewardCap,
      Optional.of(SpliceUtil.damlDecimal(0.0)),
      ic.optDevelopmentFundPercentage,
    )

  /** Copy an AmuletConfig with all issuance curve entries having faucet cap = 0. */
  private def withZeroFaucetCapConfig(
      config: AmuletConfig[USD]
  ): AmuletConfig[USD] = {
    val curve = config.issuanceCurve
    val newCurve = new Schedule[RelTime, IssuanceConfig](
      withZeroFaucetCap(curve.initialValue),
      curve.futureValues.asScala.map { entry =>
        new Tuple2(entry._1, withZeroFaucetCap(entry._2))
      }.asJava,
    )
    new AmuletConfig(
      config.transferConfig,
      newCurve,
      config.decentralizedSynchronizer,
      config.tickDuration,
      config.packageConfig,
      config.transferPreapprovalFee,
      config.featuredAppActivityMarkerAmount,
      config.optDevelopmentFundManager,
    )
  }

  "system works with optValidatorFaucetCap=0" in { implicit env =>
    clue("Set optValidatorFaucetCap to 0 via voting flow") {
      val amuletRules = sv1Backend.getDsoInfo().amuletRules
      val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
      val newConfig = withZeroFaucetCapConfig(currentConfig)
      setAmuletConfig(Seq((None, newConfig, currentConfig)))
    }

    clue("Advance several rounds") {
      (1 to 3).foreach { _ =>
        advanceRoundsToNextRoundOpening
      }
    }

    clue("No ValidatorLivenessActivityRecord contracts should exist") {
      // This implicitly verifies that alice's ReceiveFaucetCouponTrigger did not fire —
      // that trigger is the only thing that creates ValidatorLivenessActivityRecord contracts
      // (via ValidatorLicense_RecordValidatorLivenessActivity).
      val records = sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(ValidatorLivenessActivityRecord.COMPANION)(dsoParty)
      records shouldBe empty
    }

    clue("ValidatorLicenseMetadataTrigger still updates metadata") {
      val license =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(ValidatorLicense.COMPANION)(aliceValidatorBackend.getValidatorPartyId())
      license.data.metadata.toScala should not be empty
    }

    clue("Mining rounds still transition to issuing") {
      val issuingRounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
      issuingRounds should not be empty
    }
  }
}
