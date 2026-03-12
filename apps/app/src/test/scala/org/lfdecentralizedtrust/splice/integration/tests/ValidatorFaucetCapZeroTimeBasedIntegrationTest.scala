// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.IssuanceConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  DisclosedContracts,
  SpliceUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.schedule.Schedule
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
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
            .withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )

  private def allFaucetTriggers(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[Trigger] =
    Seq(
      sv1ValidatorBackend,
      aliceValidatorBackend,
      bobValidatorBackend,
      splitwellValidatorBackend,
    ).map(
      _.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
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
      val currentConfig =
        AmuletConfigSchedule(amuletRules)
          .getConfigAsOf(env.environment.clock.now)
      val newConfig = withZeroFaucetCapConfig(currentConfig)
      setAmuletConfig(Seq((None, newConfig, currentConfig)))
    }

    clue("Advance rounds so new rounds carry cap=0 issuanceConfig") {
      (1 to 3).foreach { _ =>
        advanceRoundsToNextRoundOpening
      }
    }

    // Resume faucet triggers now that all open rounds have cap=0.
    // If our workaround is correct, the triggers will skip these
    // rounds and no ValidatorLivenessActivityRecord gets created.
    setTriggersWithin(
      triggersToResumeAtStart = allFaucetTriggers,
      triggersToPauseAtStart = Seq.empty,
    ) {
      clue("Advance several more rounds with triggers resumed") {
        (1 to 3).foreach { _ =>
          advanceRoundsToNextRoundOpening
        }
      }

      clue("No ValidatorLivenessActivityRecord contracts should exist") {
        // ReceiveFaucetCouponTrigger is the only creator of these
        // records. Because the trigger skips rounds with cap=0,
        // no records should have been created.
        val records =
          sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(
              ValidatorLivenessActivityRecord.COMPANION
            )(dsoParty)
        records shouldBe empty
      }
    }

    clue("ValidatorLicenseMetadataTrigger still updates metadata") {
      val license =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(ValidatorLicense.COMPANION)(
            aliceValidatorBackend.getValidatorPartyId()
          )
      license.data.metadata.toScala should not be empty
    }

    clue("Mining rounds still transition to issuing") {
      val issuingRounds =
        sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
      issuingRounds should not be empty
    }
  }

  "SV handles stale ValidatorLivenessActivityRecord with faucet cap=0" in { implicit env =>
    clue("Set optValidatorFaucetCap to 0 via voting flow") {
      val amuletRules = sv1Backend.getDsoInfo().amuletRules
      val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
      val newConfig = withZeroFaucetCapConfig(currentConfig)
      setAmuletConfig(Seq((None, newConfig, currentConfig)))
    }

    clue("Advance rounds so new rounds with cap=0 issuanceConfig are created") {
      (1 to 3).foreach { _ =>
        advanceRoundsToNextRoundOpening
      }
    }

    clue("Manually create a ValidatorLivenessActivityRecord on a cap=0 round") {
      // Simulates a validator that hasn't picked up the workaround and still
      // exercises ValidatorLicense_RecordValidatorLivenessActivity.
      val validatorParty = aliceValidatorBackend.getValidatorPartyId()
      val license =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(ValidatorLicense.COMPANION)(validatorParty)

      // Find an open round that has cap=0 in its issuanceConfig
      val openRounds = sv1ScanBackend
        .getOpenAndIssuingMiningRounds()
        ._1
        .filter(r =>
          r.payload.opensAt.isBefore(getLedgerTime.toInstant) &&
            r.payload.issuanceConfig.optValidatorFaucetCap.toScala
              .exists(_.compareTo(java.math.BigDecimal.ZERO) <= 0)
        )
      openRounds should not be empty withClue "expected open rounds with faucet cap=0"

      val targetRound = openRounds.minBy(_.payload.round.number)

      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitJava(
          actAs = Seq(validatorParty),
          commands = license.id
            .exerciseValidatorLicense_RecordValidatorLivenessActivity(
              targetRound.contractId
            )
            .commands
            .asScala
            .toSeq,
          readAs = Seq(validatorParty),
          disclosedContracts =
            DisclosedContracts.forTesting(targetRound).toLedgerApiDisclosedContracts,
        )

      eventually() {
        val records = sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(ValidatorLivenessActivityRecord.COMPANION)(dsoParty)
        records should have size 1
      }
    }

    clue(
      "Advance rounds — SV should summarize without failing despite stale record on cap=0 round"
    ) {
      (1 to 5).foreach { _ =>
        advanceRoundsToNextRoundOpening
      }
    }

    clue("Mining rounds still transition to issuing") {
      val issuingRounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
      issuingRounds should not be empty
    }

    clue("Stale ValidatorLivenessActivityRecord should be expired") {
      eventually() {
        val records = sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(ValidatorLivenessActivityRecord.COMPANION)(dsoParty)
        records shouldBe empty
      }
    }
  }
}
