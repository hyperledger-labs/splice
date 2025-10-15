package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedReward
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.BuildInfo
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName

import scala.jdk.OptionConverters.*

class ValidatorLicenseMetadataTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        config.copy(
          validatorApps = config.validatorApps +
            (InstanceName.tryCreate("aliceValidatorLocal") ->
              config
                .validatorApps(InstanceName.tryCreate("aliceValidator"))
                .copy(
                  contactPoint = "aliceLocal@example.com"
                ))
        )
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )
      .withManualStart

  "contact point gets updated" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      aliceValidatorBackend,
    )

    val license =
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.awaitJava(
        ValidatorLicense.COMPANION
      )(aliceValidatorBackend.getValidatorPartyId())
    license.data.metadata.toScala.value shouldBe new ValidatorLicenseMetadata(
      getLedgerTime.toInstant,
      BuildInfo.compiledVersion,
      "alice@example.com",
    )
    aliceValidatorBackend.stop()
    aliceValidatorLocalBackend.start()
    actAndCheck(
      "Advance time past minMetadataUpdateInterval",
      advanceTime(java.time.Duration.ofHours(2)),
    )(
      "metadata gets updated",
      _ => {
        val license =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.awaitJava(
            ValidatorLicense.COMPANION
          )(aliceValidatorBackend.getValidatorPartyId())
        license.data.metadata.toScala.value shouldBe new ValidatorLicenseMetadata(
          getLedgerTime.toInstant,
          BuildInfo.compiledVersion,
          "aliceLocal@example.com",
        )
      },
    )
  }

  "lastActiveAt gets updated" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      aliceValidatorBackend,
    )
    // Collect the initial faucet coupons to make sure they don't run after the first license query.
    // We don't disable the trigger globally since the first test should also pass
    // with this trigger enabled.
    Seq(0, 1).foreach { _ =>
      aliceValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .runOnce()
        .futureValue
    }
    aliceValidatorBackend.validatorAutomation
      .trigger[ReceiveFaucetCouponTrigger]
      .runOnce()
      .futureValue
    val license =
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.awaitJava(
        ValidatorLicense.COMPANION
      )(aliceValidatorBackend.getValidatorPartyId())
    license.data.lastActiveAt.toScala.value shouldBe getLedgerTime.toInstant
    setTriggersWithin(
      triggersToResumeAtStart = Seq.empty,
      triggersToPauseAtStart = Seq(
        aliceValidatorBackend.validatorAutomation.trigger[ReceiveFaucetCouponTrigger]
      ),
    ) {
      actAndCheck(
        "Advance time past activityReportMinInterval",
        advanceTime(java.time.Duration.ofHours(2)),
      )(
        "lastActiveAt gets updated",
        _ => {
          val newLicense =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .awaitJava(
                ValidatorLicense.COMPANION
              )(aliceValidatorBackend.getValidatorPartyId())
          newLicense.data.lastActiveAt should not be license.data.lastActiveAt
          newLicense.data.lastActiveAt.toScala.value shouldBe getLedgerTime.toInstant
          newLicense.data.faucetState shouldBe license.data.faucetState
        },
      )
      succeed
    }
  }

  "unclaimed ValidatorLivenessActivityRecord contracts should be expired" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      aliceValidatorBackend,
    )

    // advance rounds for the reward triggers to run
    advanceRoundsToNextRoundOpening

    eventually() {
      val validatorLivenessActivityRecordRounds =
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(ValidatorLivenessActivityRecord.COMPANION)(
            dsoParty
          )
          .map(_.data.round.number)
          .toSet

      validatorLivenessActivityRecordRounds shouldBe Set(0L, 1L)
    }

    // pause the trigger to avoid collecting further rewards
    setTriggersWithin(
      triggersToResumeAtStart = Seq.empty,
      triggersToPauseAtStart = Seq(
        aliceValidatorBackend.validatorAutomation.trigger[ReceiveFaucetCouponTrigger]
      ),
    ) {

      actAndCheck(
        "Advance rounds so that issuing rounds 0 and 1 no longer exist",
        (1 to 5).foreach { _ =>
          advanceRoundsToNextRoundOpening
        },
      )(
        "ValidatorLivenessActivityRecord contracts for round 0 and 1 should be expired",
        _ => {
          val issuingRounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
          issuingRounds.map(_.payload.round.number).toSet shouldBe Set(2L, 3L, 4L)

          val allValidatorLivenessActivityRecord =
            sv1Backend.participantClient.ledger_api_extensions.acs
              .filterJava(ValidatorLivenessActivityRecord.COMPANION)(
                dsoParty
              )
          allValidatorLivenessActivityRecord shouldBe empty

          // assuming this value is the same in that of round 0 and 1
          val issuancePerValidatorFaucetCoupon =
            issuingRounds.headOption.value.payload.optIssuancePerValidatorFaucetCoupon.toScala.value

          val unclaimedRewards = sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(UnclaimedReward.COMPANION)(
              dsoParty
            )
            .map(_.data.amount)

          unclaimedRewards.count(_ == issuancePerValidatorFaucetCoupon) shouldBe 2
        },
      )
      succeed
    }
  }
}
