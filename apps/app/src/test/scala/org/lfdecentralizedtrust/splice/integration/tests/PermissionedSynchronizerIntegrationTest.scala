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
    withClue("onboard SV1 in RestrictedOpen mode") {
      initDsoWithSv1Only()

      val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
        .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)

      currentParams.onboardingRestriction shouldBe RestrictedOpen
    }

    withClue("allow SV1 to authorize and start follower SVs 2-4 sequentially") {
      val followerSvs = Seq(
        (sv2ValidatorBackend, sv2Backend, sv2ScanBackend),
        (sv3ValidatorBackend, sv3Backend, sv3ScanBackend),
        (sv4ValidatorBackend, sv4Backend, sv4ScanBackend),
      )

      var authorizedSvs = Seq(sv1ValidatorBackend)

      for ((validator, sv, scan) <- followerSvs) {
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
          startAllSync(sv, scan, validator)
        }
        authorizedSvs :+= validator
      }
    }

    withClue("Grant validator permission to Alice and verify visibility across all SVs") {

      val aliceParticipantId = aliceValidatorBackend.participantClient.id

      val allSvValidators =
        Seq(sv1ValidatorBackend, sv2ValidatorBackend, sv3ValidatorBackend, sv4ValidatorBackend)

      actAndCheck(
        "Grant validator permission to Alice",
        sv1Backend.grantValidatorPermission(aliceParticipantId.adminParty, aliceParticipantId),
      )(
        "Verify confirmed topology permission across all SVs",
        _ => {
          for (svValidator <- allSvValidators) {
            clue(s"Checking active topology state on ${svValidator.name}") {
              val permissions =
                svValidator.participantClient.topology.participant_synchronizer_permissions
                  .list(
                    store = decentralizedSynchronizerId,
                    filterUid = aliceParticipantId.filterString,
                  )
              permissions.map(_.item.permission) should contain(
                ParticipantPermission.Submission
              ) // reports errors in log for premature checks
            }
          }
        },
      )
    }

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
