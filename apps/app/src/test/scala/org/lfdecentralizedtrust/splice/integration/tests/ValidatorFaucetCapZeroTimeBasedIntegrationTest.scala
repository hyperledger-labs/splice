// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.{
  ValidatorLivenessActivityRecord,
  ValidatorLicense,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, TimeTestUtil, WalletTestUtil}

import scala.jdk.CollectionConverters.*

class ValidatorFaucetCapZeroTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // Found the network with optValidatorFaucetCap=0 so all rounds
      // carry cap=0 from the start — no voting flow needed.
      .addConfigTransform((_, config) => ConfigTransforms.withValidatorFaucetCap(0)(config))

  "system works with optValidatorFaucetCap=0 and handles stale liveness records" in {
    implicit env =>
      clue("Advance several rounds") {
        (1 to 3).foreach { _ =>
          advanceRoundsToNextRoundOpening
        }
      }

      clue("No ValidatorLivenessActivityRecord contracts should exist") {
        // ReceiveFaucetCouponTrigger is the only creator of these
        // records. Because the trigger skips rounds with cap=0,
        // no records should have been created.
        val records = sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(ValidatorLivenessActivityRecord.COMPANION)(dsoParty)
        records shouldBe empty
      }

      clue("Manually create a ValidatorLivenessActivityRecord on a cap=0 round") {
        // Simulates a validator that hasn't picked up the workaround and still
        // exercises ValidatorLicense_RecordValidatorLivenessActivity.
        val validatorParty = aliceValidatorBackend.getValidatorPartyId()
        val license =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(ValidatorLicense.COMPANION)(validatorParty)

        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt.isBefore(getLedgerTime.toInstant))
        openRounds should not be empty

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

      clue("Stale ValidatorLivenessActivityRecord should be expired") {
        eventually() {
          val records = sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(ValidatorLivenessActivityRecord.COMPANION)(dsoParty)
          records shouldBe empty
        }
      }
  }
}
