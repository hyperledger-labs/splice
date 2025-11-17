// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.MergeValidatorLicenseContractsTrigger
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil.{
  pauseAllDsoDelegateTriggers,
  resumeAllDsoDelegateTriggers,
}
import org.slf4j.event.Level
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

class SvValidatorLicenseIntegrationTest extends SvIntegrationTestBase with TriggerTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  "grant validator license and verify merged licenses" in { implicit env =>
    val dsoParty = sv1Backend.getDsoInfo().dsoParty
    val sv1Party = sv1Backend.getDsoInfo().svParty

    def getLicensesFromAliceValidator(p: PartyId) = {
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(ValidatorLicense.COMPANION)(
          dsoParty,
          c => c.data.validator == p.toProtoPrimitive,
        )
    }

    // Allocate a new party on aliceValidator
    // Specify the decentralized synchronizer ID as alice seems to be connected to multiple domains
    val synchronizerId = sv1Backend.participantClient.synchronizers
      .list_connected()
      .find(_.synchronizerAlias == sv1Backend.config.domains.global.alias)
      .getOrElse(throw new IllegalStateException("synchronizer not found"))
      .synchronizerId

    val partyIdHint = "alice-test-party-2"
    val newParty =
      try {
        aliceValidatorBackend.participantClient.ledger_api.parties
          .allocate(partyIdHint, synchronizerId = Some(synchronizerId))
          .party
      } catch {
        case NonFatal(e) =>
          aliceValidatorBackend.participantClient.ledger_api.parties
            .list()
            .find(_.party.toProtoPrimitive.startsWith(partyIdHint))
            .getOrElse(fail(e))
            .party
      }

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
      "Two ValidatorLicenses exist for Alice",
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
}
