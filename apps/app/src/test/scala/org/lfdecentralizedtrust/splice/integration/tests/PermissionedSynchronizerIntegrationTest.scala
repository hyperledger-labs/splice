// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.{
  RestrictedOpen,
  UnrestrictedOpen,
}
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{PostgresAroundEach, ProcessTestUtil, WalletTestUtil}

import scala.concurrent.duration.DurationInt

/*
 * GOAL: This test aims the check the required canton properties: ParticipantSynchronizerPermission and OnboardingRestriction
 *
 * Steps
 * 1. Start a synchronizer with only SV1
 * 2. Create a ParticipantSynchronizerPermission for the SV1 validator participant
 * 3. Set the onboarding restriction to RestrictedOpen
 * 4. Submit a ParticipantSynchronizerPermission for the alice participant
 * 5. Start the alice participant
 * */

class PermissionedSynchronizerIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with PostgresAroundEach
    with WalletTestUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def usesDbs: Seq[String] = Seq(
    "alice_participant_new"
  )

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .withAllocatedUsers(extraIgnoredValidatorPrefixes = Seq("aliceValidator"))
      .addConfigTransforms(
        (_, config) =>
          ConfigTransforms.bumpSomeValidatorAppPortsBy(22_000, Seq("aliceValidator"))(config),
        (_, config) =>
          ConfigTransforms.bumpSomeWalletClientPortsBy(22_000, Seq("aliceWallet"))(config),
      )
      .withManualStart

  "onboard validator in permissioned mode" in { implicit env =>
    initDsoWithSv1Only()
    // start an extra participant

    // create a ParticipantSynchronizerPermission for the SV1 validator participant

    withClue("Phase 1: Set ParticipantSynchronizerPermission for SV1") {

      sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions.find(
        decentralizedSynchronizerId,
        sv1ValidatorBackend.participantClientWithAdminToken.id,
      ) match {
        case Some(res)
            if res.item.permission == ParticipantPermission.Submission => {} // ParticipantSynchronizerPermission already exists

        case Some(res) =>
          sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions
            .propose(
              decentralizedSynchronizerId,
              sv1ValidatorBackend.participantClientWithAdminToken.id,
              permission = ParticipantPermission.Submission,
              serial = Some(res.context.serial.increment),
            )

        case None =>
          sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions
            .propose(
              decentralizedSynchronizerId,
              sv1ValidatorBackend.participantClientWithAdminToken.id,
              permission = ParticipantPermission.Submission,
              serial = Some(com.digitalasset.canton.config.RequireTypes.PositiveInt.one),
            )
      }

      eventually(60.seconds, 1.second) {
        val authorized =
          sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions
            .find(
              decentralizedSynchronizerId,
              sv1ValidatorBackend.participantClientWithAdminToken.id,
            )
        authorized.exists(_.item.permission == ParticipantPermission.Submission) shouldBe true
      }

    }

    withClue("Phase 2: change onboarding restriction to RestrictedOpen") {

      // set the onboarding restriction to RestrictedOpen

      if (
        sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
          .onboardingRestriction != RestrictedOpen
      ) {
        sv1ValidatorBackend.participantClient.topology.synchronizer_parameters.propose_update(
          decentralizedSynchronizerId,
          _.update(onboardingRestriction = RestrictedOpen),
        )
      }

      eventually(60.seconds, 1.second) {
        val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)

        currentParams.onboardingRestriction shouldBe RestrictedOpen
      }
    }

    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf"
      ),
      Seq(
      ),
      "aliceValidatorExtra",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> "alice_participant_new",
    ) {

      withClue("Phase 3: Submit a ParticipantSynchronizerPermission for the alice participant") {

        // create a ParticipantSynchronizerPermission for the alice participant

        val aliceParticipantClient = new ParticipantClientReference(
          env,
          "extraStandaloneParticipant",
          com.digitalasset.canton.participant.config.RemoteParticipantConfig(
            adminApi = com.digitalasset.canton.config.FullClientConfig(
              port = com.digitalasset.canton.config.RequireTypes.Port.tryCreate(27502)
            ),
            ledgerApi = com.digitalasset.canton.config.FullClientConfig(
              port = com.digitalasset.canton.config.RequireTypes.Port.tryCreate(27501)
            ),
          ),
        )

        aliceParticipantClient.health.wait_for_ready_for_id()

        val aliceRootKey = aliceParticipantClient.keys.secret
          .generate_signing_key(
            "aliceRootKey",
            com.digitalasset.canton.crypto.SigningKeyUsage.NamespaceOnly,
          )
        val aliceNamespace = com.digitalasset.canton.topology.Namespace(aliceRootKey.fingerprint)

        aliceParticipantClient.topology.init_id_from_uid(
          com.digitalasset.canton.topology.ParticipantId("aliceValidator", aliceNamespace).uid,
          waitForReady = true,
        )

        aliceParticipantClient.topology.namespace_delegations.propose_delegation(
          aliceNamespace,
          aliceRootKey,
          delegationRestriction =
            com.digitalasset.canton.topology.transaction.DelegationRestriction.CanSignAllMappings,
          signedBy = Seq(aliceNamespace.fingerprint),
        )

        val aliceParticipantId = aliceParticipantClient.id

        sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions.find(
          decentralizedSynchronizerId,
          aliceParticipantId,
        ) match {
          case Some(res) if res.item.permission == ParticipantPermission.Submission => {}

          case Some(res) =>
            sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions
              .propose(
                decentralizedSynchronizerId,
                aliceParticipantId,
                permission = ParticipantPermission.Submission,
                serial = Some(res.context.serial.increment),
              )

          case None =>
            sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions
              .propose(
                decentralizedSynchronizerId,
                aliceParticipantId,
                permission = ParticipantPermission.Submission,
                serial = Some(com.digitalasset.canton.config.RequireTypes.PositiveInt.one),
              )
        }

        withClue("Wait until alice topology transaction is registered") {
          eventually(60.seconds, 1.second) {
            val authorized =
              sv1ValidatorBackend.participantClient.topology.participant_synchronizer_permissions
                .find(
                  decentralizedSynchronizerId,
                  aliceParticipantId,
                )
            authorized.exists(_.item.permission == ParticipantPermission.Submission) shouldBe true
          }
        }
      }

      withClue("Phase 4: Start the alice validator participant") {

        // start the alice validator participant

        aliceValidatorBackend.startSync()

      }

      withClue("Phase 5: Reset the onboarding restriction back to UnrestrictedOpen") {

        // set the onboarding restriction back to UnrestrictedOpen

        if (
          sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
            .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
            .onboardingRestriction != UnrestrictedOpen
        ) {
          sv1ValidatorBackend.participantClient.topology.synchronizer_parameters.propose_update(
            decentralizedSynchronizerId,
            _.update(onboardingRestriction = UnrestrictedOpen),
          )
        }

        eventually(60.seconds, 1.second) {
          val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
            .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)

          currentParams.onboardingRestriction shouldBe UnrestrictedOpen
        }
      }

    }
  }
}
