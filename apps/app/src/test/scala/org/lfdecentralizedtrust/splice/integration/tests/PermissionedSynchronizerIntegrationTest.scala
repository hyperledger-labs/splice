// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.RestrictedOpen
import com.digitalasset.canton.topology.{PartyId, UniqueIdentifier}
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig
import org.lfdecentralizedtrust.splice.sv.util.{SvOnboardingToken, SvUtil}
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, SvTestUtil, WalletTestUtil}

class PermissionedSynchronizerIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with WalletTestUtil
    with SvTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs { case (_, c) =>
          c.copy(permissionedSynchronizer = true)
        }(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs { (_, config) =>
          config.copy(
            approvedSvIdentities = config.approvedSvIdentities.filter(
              _.name != getSvName(4)
            )
          )
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

    withClue("allow SV1 to authorize and start follower SVs 2-3 sequentially") {
      val followerSvs = Seq(
        (sv2ValidatorBackend, sv2Backend, sv2ScanBackend),
        (sv3ValidatorBackend, sv3Backend, sv3ScanBackend),
      )

      for ((validator, sv, scan) <- followerSvs) {
        clue(
          s"Starting SV ${validator.participantClient.id}"
        ) {
          startAllSync(sv, scan, validator)
        }
      }
    }

    withClue("Attempting to grant permission with an invalid token fails") {
      val invalidToken = "not-a-valid-base64-signed-token"

      assertThrowsAndLogsCommandFailures(
        sv1Backend.grantSvOnboardingPermission(invalidToken),
        _.errorMessage should include("Could not verify and decode token"),
      )
    }

    withClue(
      "Attempting to grant permission from a sponsor that hasn't approved the identity fails"
    ) {
      val sv4OnboardingConfig =
        sv4Backend.config.onboarding.value.asInstanceOf[SvOnboardingConfig.JoinWithKey]
      val sv4Party = PartyId(
        UniqueIdentifier.tryCreate(
          sv4OnboardingConfig.name,
          sv4ValidatorBackend.participantClient.id.uid.namespace,
        )
      )
      val privateKey = SvUtil.parsePrivateKey(sv4OnboardingConfig.privateKey).value
      val dsoParty = sv1Backend.getDsoInfo().dsoParty

      val validSv4Token = SvOnboardingToken(
        sv4OnboardingConfig.name,
        sv4OnboardingConfig.publicKey,
        sv4Party,
        sv4ValidatorBackend.participantClient.id,
        dsoParty,
      ).signAndEncode(privateKey).value
      assertThrowsAndLogsCommandFailures(
        sv1Backend.grantSvOnboardingPermission(validSv4Token),
        _.errorMessage should include("Could not approve SV Identity because"),
      )
    }

    withClue("Grant validator permission to Alice and verify visibility across all SVs") {

      val aliceParticipantId = aliceValidatorBackend.participantClient.id

      val allSvValidators =
        Seq(sv1ValidatorBackend, sv2ValidatorBackend, sv3ValidatorBackend)

      actAndCheck(
        "Grant validator permission to Alice",
        sv1Backend.grantValidatorPermission(aliceParticipantId.adminParty, aliceParticipantId),
      )(
        "Verify confirmed topology permission across all SVs",
        _ => {
          for (svValidator <- allSvValidators) {
            logger.info(s"Checking topology state on ${svValidator.name}")
            svValidator.participantClient.topology.participant_synchronizer_permissions
              .list(
                store = decentralizedSynchronizerId,
                filterUid = aliceParticipantId.filterString,
              )
              .map(_.item.permission) should contain(
              ParticipantPermission.Submission
            )

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
