// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.RestrictedOpen
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
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
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs {
          case (name, c) if name == "sv1" =>
            c.copy(permissionedSynchronizer = true)
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "onboard validator in permissioned mode" in { implicit env =>
    initDsoWithSv1Only()

    withClue("onboarding should be RestrictedOpen") {
      val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
        .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
      currentParams.onboardingRestriction shouldBe RestrictedOpen
    }

    val allSvValidators =
      Seq(sv1ValidatorBackend, sv2ValidatorBackend, sv3ValidatorBackend, sv4ValidatorBackend)

    val followerSvValidators =
      Seq(sv2ValidatorBackend, sv3ValidatorBackend, sv4ValidatorBackend)

    withClue("Submit ParticipantSynchronizerPermission for SV2-4 across all SV participants") {
      for {
        submitter <- allSvValidators
        target <- followerSvValidators
      } {
        submitter.participantClient.topology.participant_synchronizer_permissions
          .propose(
            decentralizedSynchronizerId,
            target.participantClientWithAdminToken.id,
            permission = ParticipantPermission.Submission,
          )
      }
    }

    withClue("Start SV2-4") {
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
