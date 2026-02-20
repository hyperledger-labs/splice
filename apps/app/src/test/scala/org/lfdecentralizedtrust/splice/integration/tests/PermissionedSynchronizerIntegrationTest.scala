// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.RestrictedOpen
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, WalletTestUtil}

class PermissionedSynchronizerIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart

  "onboard validator in permissioned mode" in { implicit env =>
    initDso()

    withClue("Stop all SVs") {
      sv1Backend.stop()
      sv2Backend.stop()
      sv3Backend.stop()
      sv4Backend.stop()
    }

    val allSvValidators =
      Seq(sv1ValidatorBackend, sv2ValidatorBackend, sv3ValidatorBackend, sv4ValidatorBackend)

    withClue("Submit ParticipantSynchronizerPermission for all SVs across all SV participants") {
      for {
        submitter <- allSvValidators
        target <- allSvValidators
      } {
        submitter.participantClient.topology.participant_synchronizer_permissions
          .propose(
            decentralizedSynchronizerId,
            target.participantClientWithAdminToken.id,
            permission = ParticipantPermission.Submission,
          )
      }
    }

    withClue("change onboarding restriction to RestrictedOpen") {
      actAndCheck(
        "Propose RestrictedOpen onboarding restriction",
        for (sv <- allSvValidators) {
          sv.participantClient.topology.synchronizer_parameters.propose_update(
            decentralizedSynchronizerId,
            _.update(onboardingRestriction = RestrictedOpen),
          )
        },
      )(
        "Verify parameters are updated to RestrictedOpen",
        _ => {
          val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
            .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)

          currentParams.onboardingRestriction shouldBe RestrictedOpen
        },
      )
    }

    withClue("Restart all SVs") {
      sv1Backend.startSync()
      sv2Backend.startSync()
      sv3Backend.startSync()
      sv4Backend.startSync()
    }

    withClue("Submit a ParticipantSynchronizerPermission for the alice participant") {
      val aliceParticipantId = aliceValidatorBackend.participantClient.id

      val firstThreeSvs = allSvValidators.take(3) // we just need 2f+1 SVs to submit this.

      for (sv <- firstThreeSvs) {
        sv.participantClient.topology.participant_synchronizer_permissions
          .propose(
            decentralizedSynchronizerId,
            aliceParticipantId,
            permission = ParticipantPermission.Submission,
          )
      }
    }

    withClue("Start the alice validator participant") {
      actAndCheck(
        "Start Alice validator",
        aliceValidatorBackend.startSync(),
      )(
        "Onboard Alice test user",
        _ => {
          aliceValidatorBackend.onboardUser("TestUser")
        },
      )
    }
  }
}
