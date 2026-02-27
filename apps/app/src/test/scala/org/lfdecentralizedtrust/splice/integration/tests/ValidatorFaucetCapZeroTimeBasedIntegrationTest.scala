package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLivenessActivityRecord
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}

import scala.jdk.OptionConverters.*

class ValidatorFaucetCapZeroTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        ConfigTransforms.withValidatorFaucetCap(BigDecimal(0))(config)
      )

  "system operates correctly with optValidatorFaucetCap=0" in { implicit env =>
    // Advance time so reward automation runs for current round
    advanceTimeForRewardAutomationToRunForCurrentRound

    // Verify ValidatorLivenessActivityRecord contracts are created (trigger still runs)
    eventually() {
      val activityRecords =
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(ValidatorLivenessActivityRecord.COMPANION)(
            dsoParty
          )
      activityRecords should not be empty
    }

    // Advance rounds so that issuing rounds are created
    (1 to 3).foreach { _ =>
      advanceRoundsToNextRoundOpening
    }

    // Verify issuing rounds have zero issuance per faucet coupon
    eventually() {
      val issuingRounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
      issuingRounds should not be empty
      issuingRounds.foreach { round =>
        val issuancePerFaucetCoupon = round.payload.optIssuancePerValidatorFaucetCoupon.toScala
        BigDecimal(issuancePerFaucetCoupon.value) shouldBe BigDecimal(0)
      }
    }

    // Advance a few more rounds to verify continued stability
    (1 to 2).foreach { _ =>
      advanceRoundsToNextRoundOpening
    }

    // Verify rounds continued advancing without errors
    eventually() {
      val (openRounds, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
      openRounds should not be empty
      issuingRounds should not be empty
    }
  }
}
