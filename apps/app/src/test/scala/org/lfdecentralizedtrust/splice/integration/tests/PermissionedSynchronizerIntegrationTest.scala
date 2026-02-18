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
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart

  "onboard validator in permissioned mode" in { implicit env =>
    initDsoWithSv1Only()

    sv1Backend.stop()

    withClue("Set ParticipantSynchronizerPermission for SV1") {

      sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions
        .propose(
          decentralizedSynchronizerId,
          sv1ValidatorBackend.participantClientWithAdminToken.id,
          permission = ParticipantPermission.Submission,
        )
    }

    withClue("change onboarding restriction to RestrictedOpen") {
      actAndCheck(
        "Propose RestrictedOpen onboarding restriction",
        sv1ValidatorBackend.participantClient.topology.synchronizer_parameters.propose_update(
          decentralizedSynchronizerId,
          _.update(onboardingRestriction = RestrictedOpen),
        ),
      )(
        "Verify parameters are updated to RestrictedOpen",
        _ => {
          val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
            .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)

          currentParams.onboardingRestriction shouldBe RestrictedOpen
        },
      )
    }

    sv1Backend.startSync()

    // only the following are what we want to exercise in this integration test

    withClue("Submit a ParticipantSynchronizerPermission for the alice participant") {
      val aliceParticipantId = aliceValidatorBackend.participantClient.id

      sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions
        .propose(
          decentralizedSynchronizerId,
          aliceParticipantId,
          permission = ParticipantPermission.Submission,
        )
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
