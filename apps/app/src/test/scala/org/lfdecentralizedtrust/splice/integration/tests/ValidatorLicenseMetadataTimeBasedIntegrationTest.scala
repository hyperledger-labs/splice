package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedReward
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.validatorlicensechange.VLC_ChangeWeight
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.BuildInfo
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{
  SvTestUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName

import scala.jdk.OptionConverters.*

class ValidatorLicenseMetadataTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with SvTestUtil {

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
    advanceTimeForRewardAutomationToRunForCurrentRound

    val licenseWithLastActiveForCurrentRound = eventually() {
      val license =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.awaitJava(
          ValidatorLicense.COMPANION
        )(aliceValidatorBackend.getValidatorPartyId())
      license.data.lastActiveAt.toScala.value shouldBe getLedgerTime.toInstant
      license.data.faucetState.toScala.value.lastReceivedFor.number shouldBe sv1ScanBackend
        .getOpenAndIssuingMiningRounds()
        ._1
        .filter(
          _.payload.opensAt.isBefore(getLedgerTime.toInstant)
        )
        .map(_.payload.round.number)
        .maxOption
        .value
      license
    }
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
          newLicense.data.lastActiveAt should not be licenseWithLastActiveForCurrentRound.data.lastActiveAt
          newLicense.data.lastActiveAt.toScala.value shouldBe getLedgerTime.toInstant
          newLicense.data.faucetState shouldBe licenseWithLastActiveForCurrentRound.data.faucetState
        },
      )
      succeed
    }
  }

  "expired ValidatorLivenessActivityRecord creates UnclaimedReward with correct weight" in {
    implicit env =>
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
        aliceValidatorBackend,
      )

      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

      // Change Alice's validator license weight to 10.0
      val aliceNewWeight = BigDecimal(10.0)
      actAndCheck(
        "Modify validator licenses",
        modifyValidatorLicenses(
          sv1Backend,
          svsToCastVotes = Seq.empty,
          Seq(new VLC_ChangeWeight(aliceValidatorParty.toProtoPrimitive, aliceNewWeight.bigDecimal)),
        ),
      )(
        "validator license modifications have been applied",
        _ => {
          val licenses =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(ValidatorLicense.COMPANION)(
                dsoParty,
                c => c.data.validator == aliceValidatorParty.toProtoPrimitive,
              )
          licenses should have length 1
          licenses.head.data.weight.toScala.map(BigDecimal(_)) shouldBe Some(aliceNewWeight)
        },
      )

      advanceTimeForRewardAutomationToRunForCurrentRound

      // Verify liveness activity records are created for with correct weight
      val aliceActivityRecords = eventually() {
        val records = sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(ValidatorLivenessActivityRecord.COMPANION)(
            dsoParty,
            c => c.data.validator == aliceValidatorParty.toProtoPrimitive,
          )
        records.map(_.data.round.number).toSet shouldBe Set(0L, 1L)
        records
      }

      aliceActivityRecords.foreach { record =>
        record.data.weight.toScala.map(BigDecimal(_)) shouldBe Some(aliceNewWeight)
      }

      val recordRounds = aliceActivityRecords.map(_.data.round.number).toSet

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
            // Verify activity records are expired
            val allValidatorLivenessActivityRecord =
              sv1Backend.participantClient.ledger_api_extensions.acs
                .filterJava(ValidatorLivenessActivityRecord.COMPANION)(
                  dsoParty
                )
            allValidatorLivenessActivityRecord shouldBe empty

            // Get issuance value from issuing rounds
            val issuingRounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
            issuingRounds should not be empty

            val issuancePerValidatorFaucetCoupon =
              issuingRounds.headOption.value.payload.optIssuancePerValidatorFaucetCoupon.toScala.value

            // Expected amount per record is weight * issuance
            val expectedAmountPerRecord =
              aliceNewWeight.bigDecimal.multiply(issuancePerValidatorFaucetCoupon)

            val unclaimedRewards = sv1Backend.participantClient.ledger_api_extensions.acs
              .filterJava(UnclaimedReward.COMPANION)(dsoParty)
              .map(_.data.amount)

            // Should have unclaimed rewards with the weighted amount for each round
            unclaimedRewards.count(
              _.compareTo(expectedAmountPerRecord) == 0
            ) shouldBe recordRounds.size
          },
        )
        succeed
      }
  }
}
