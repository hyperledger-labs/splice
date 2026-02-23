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

  "A permissioned synchronizer" should {

    "onboard SV1 in RestrictedOpen mode" in { implicit env =>
      initDsoWithSv1Only()
      sv2ScanBackend.startSync()
      sv3ScanBackend.startSync()
      sv4ScanBackend.startSync()

      val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
        .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)

      currentParams.onboardingRestriction shouldBe RestrictedOpen
    }

    "allow SV1 to authorize and start follower SVs 2-4 sequentially" in { implicit env =>
      val followerSvs = Seq(
        (sv2ValidatorBackend, sv2Backend),
        (sv3ValidatorBackend, sv3Backend),
        (sv4ValidatorBackend, sv4Backend),
      )

      var authorizedSvs = Seq(sv1ValidatorBackend)

      for ((validator, sv) <- followerSvs) {
        for (submitter <- authorizedSvs) {
          clue(
            "Submitting participantSynchronizerPermission of " + validator.participantClient.id + " to SV" + submitter.participantClient.id
          ) {
            submitter.participantClient.topology.participant_synchronizer_permissions
              .propose(
                decentralizedSynchronizerId,
                validator.participantClientWithAdminToken.id,
                permission = ParticipantPermission.Submission,
              )
          }
        }
        clue("Starting SV" + validator.participantClient.id) {
          sv.startSync()
          validator.startSync()
        }
        authorizedSvs :+= validator
      }
    }

    "require a 2f+1 majority of SVs to authorize the Alice validator" in { implicit env =>
      val aliceParticipantId = aliceValidatorBackend.participantClient.id
      val quorumSvs = Seq(sv1ValidatorBackend, sv2ValidatorBackend, sv3ValidatorBackend)

      for (sv <- quorumSvs) {
        sv.participantClient.topology.participant_synchronizer_permissions
          .propose(
            decentralizedSynchronizerId,
            aliceParticipantId,
            permission = ParticipantPermission.Submission,
          )
      }
    }

    "allow Alice to join and onboard users once authorized" in { implicit env =>
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
