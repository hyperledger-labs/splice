// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.validatorlicensechange.{
  VLC_ChangeWeight,
  VLC_Withdraw,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.MergeValidatorLicenseContractsTrigger
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil.{
  pauseAllDsoDelegateTriggers,
  resumeAllDsoDelegateTriggers,
}
import org.slf4j.event.Level
import scala.jdk.OptionConverters.*

class SvValidatorLicenseIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  "grant validator license and verify merged licenses" in { implicit env =>
    val info = sv1Backend.getDsoInfo()
    val dsoParty = info.dsoParty
    val sv1Party = info.svParty

    def getLicensesFromAliceValidator(p: PartyId) = {
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(ValidatorLicense.COMPANION)(
          dsoParty,
          c => c.data.validator == p.toProtoPrimitive,
        )
    }

    // Allocate a new external party on aliceValidator
    val OnboardingResult(newParty, _, _) =
      onboardExternalParty(aliceValidatorBackend, Some("alice-test-party-2"))

    // Test 1: Can allocate license to a new party on aliceValidator

    val licenses = getLicensesFromAliceValidator(newParty)
    licenses should have length 0

    actAndCheck(
      "Grant validator license to random new party on aliceValidator",
      sv1Backend.grantValidatorLicense(newParty),
    )(
      "ValidatorLicense granted to a random new party",
      _ => {
        val licenses = getLicensesFromAliceValidator(newParty)

        licenses should have length 1
        licenses.head.data.validator shouldBe newParty.toProtoPrimitive
        licenses.head.data.sponsor shouldBe sv1Party.toProtoPrimitive
      },
    )

    // Test 2: Verify that granting new license to existing validator merges all the
    // licenses to one, and does not affect its kind

    val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
    val initialAliceLicenses = getLicensesFromAliceValidator(aliceValidatorParty)
    initialAliceLicenses should have length 1
    val initialKind = initialAliceLicenses.head.data.kind.toScala

    // Pause the merge trigger to confirm multiple licenses exist
    pauseAllDsoDelegateTriggers[MergeValidatorLicenseContractsTrigger]

    // Grant a license to Alice (creates NonOperatorLicense) with trigger paused
    actAndCheck(
      "Grant license to an onboarded validator",
      sv1Backend.grantValidatorLicense(aliceValidatorParty),
    )(
      "Two ValidatorLicenses exist for alice validator party",
      _ => {
        val licenses = getLicensesFromAliceValidator(aliceValidatorParty)

        licenses should have length 2
      },
    )

    // Resume the merge trigger and verify licenses got merged
    // The trigger can process both validator licenses in parallel so we might get multiple log messages.
    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        actAndCheck(
          "Resume merge trigger",
          resumeAllDsoDelegateTriggers[MergeValidatorLicenseContractsTrigger],
        )(
          "Licenses are merged while maintaining kind",
          _ => {
            val licenses = getLicensesFromAliceValidator(aliceValidatorParty)

            licenses should have length 1
            licenses.head.data.validator shouldBe aliceValidatorParty.toProtoPrimitive
            licenses.head.data.kind.toScala shouldBe initialKind
          },
        )
        // Pause to make sure we don't get more log messages
        pauseAllDsoDelegateTriggers[MergeValidatorLicenseContractsTrigger]
      },
      forAll(_)(
        _.warningMessage should include(
          "has 2 Validator License contracts."
        )
      ),
    )
  }

  "can do batch modification of validator licenses" in { implicit env =>
    val info = sv1Backend.getDsoInfo()
    val dsoParty = info.dsoParty

    def getLicenses(p: PartyId) = {
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(ValidatorLicense.COMPANION)(
          dsoParty,
          c => c.data.validator == p.toProtoPrimitive,
        )
    }

    // Allocate two external parties on aliceValidator
    val OnboardingResult(newParty1, _, _) =
      onboardExternalParty(aliceValidatorBackend, Some("alice-test-party-1"))
    val OnboardingResult(newParty2, _, _) =
      onboardExternalParty(aliceValidatorBackend, Some("alice-test-party-2"))

    // Grant licenses to both validators
    actAndCheck(
      "Grant validator license to first validator",
      sv1Backend.grantValidatorLicense(newParty1),
    )(
      "ValidatorLicense granted with default weight",
      _ => {
        val licenses = getLicenses(newParty1)
        licenses should have length 1
        licenses.head.data.weight.toScala shouldBe None
      },
    )

    actAndCheck(
      "Grant validator license to second validator",
      sv1Backend.grantValidatorLicense(newParty2),
    )(
      "ValidatorLicense granted",
      _ => {
        val licenses = getLicenses(newParty2)
        licenses should have length 1
        licenses.head.data.weight.toScala shouldBe None
      },
    )

    // Create a vote for batch modification, with both weight change and withdrawal
    import env.executionContext
    modifyValidatorLicensesWithVoting(
      sv1Backend,
      svsToCastVotes = Seq.empty,
      Seq(
        new VLC_ChangeWeight(newParty1.toProtoPrimitive, BigDecimal(10.0).bigDecimal),
        new VLC_Withdraw(newParty2.toProtoPrimitive),
      ),
    ) {
      val licenses1 = getLicenses(newParty1)
      licenses1 should have length 1
      licenses1.head.data.weight.toScala shouldBe Some(BigDecimal("10.0000000000").bigDecimal)

      val licenses2 = getLicenses(newParty2)
      licenses2 shouldBe empty
    }
  }
}
