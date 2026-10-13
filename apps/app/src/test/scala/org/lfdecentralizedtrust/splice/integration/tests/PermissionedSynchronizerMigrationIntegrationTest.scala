// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.RestrictedOpen
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{
  ProcessTestUtil,
  SwitchOverTimes,
  SynchronizerFeesTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRulesConfig,
  DsoRules_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_SetConfig
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.SynchronizerPermissionState
import org.slf4j.event.Level

import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.concurrent.duration.*

class PermissionedSynchronizerMigrationIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with WalletTestUtil
    with SynchronizerFeesTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withTrafficTopupsDisabled
      .withManualStart

  "Migrate Network from UnrestrictedOpen to RestrictedOpen" in { implicit env =>
    initDso()

    clue("Initially no participant has ParticipantSynchronizerPermission") {
      Seq(
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
        aliceValidatorBackend,
        bobValidatorBackend,
      ).foreach { app =>
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          app.participantClient.id.toProtoPrimitive,
        ) shouldBe None
      }
    }

    def buyMemberTraffic(validator: ValidatorAppBackendReference): Unit = {
      val trafficAmount = Math.max(
        sv1ScanBackend
          .getAmuletConfigAsOf(env.environment.clock.now)
          .decentralizedSynchronizer
          .fees
          .minTopupAmount
          .toLong,
        1_000_000L,
      )

      val sv1WalletUserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
      sv1WalletClient.tap(10000)

      clue(s"SV1 buys MemberTraffic for ${validator.participantClient.name}") {
        createBuyTrafficRequest(
          validatorApp = sv1ValidatorBackend,
          buyer = sv1WalletUserParty,
          memberId = validator.participantClient.id.toProtoPrimitive,
          trafficAmount = trafficAmount,
          trackingId = s"traffic-for-${validator.participantClient.name}",
        )
      }
    }

    clue("Start Alice validator") {
      aliceValidatorBackend.startSync()
    }

    clue("Vote for a switch-over-time in the future for permissionedSynchronizer") {
      setPermissionedSynchronizerSwitchOverTime()

      eventually(40.seconds) {
        sv1ScanBackend
          .getDsoInfo()
          .dsoRules
          .payload
          .config
          .svOperationsSwitchOverTimes
          .toScala
          .map(_.asScala.toMap)
          .value should contain key SwitchOverTimes.PermissionedSynchronizer
      }
    }

    clue("Verify SVs and Alice have permissions granted") {
      Seq(
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
        aliceValidatorBackend,
      ).foreach { app =>
        clue(
          s"Checking PermissionSynchronizerPermission for ${app.participantClient.id.toProtoPrimitive}"
        ) {
          eventually(40.seconds) {
            val permissionAssigned = sv1ScanBackend.getParticipantSynchronizerPermission(
              decentralizedSynchronizerId.toProtoPrimitive,
              app.participantClient.id.toProtoPrimitive,
            )
            permissionAssigned shouldBe Some(SynchronizerPermissionState(None))
          }
        }
      }
    }

    clue("Assert RestrictedOpen is set") {
      eventually(40.seconds) {
        val currentParams = sv1ValidatorBackend.participantClient.topology.synchronizer_parameters
          .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
        currentParams.onboardingRestriction shouldBe RestrictedOpen
      }
    }

    clue("Alice can onboard a user") {
      aliceValidatorBackend.onboardUser("alice-user")
    }

    clue("Start Bob") {
      bobValidatorBackend.start()
    }

    buyMemberTraffic(bobValidatorBackend)

    clue("Bob now has ParticipantSynchronizerPermission") {
      eventually(40.seconds) {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobValidatorBackend.participantClient.id.toProtoPrimitive,
        ) shouldBe Some(SynchronizerPermissionState(None))
      }
    }
    clue("Bob can onboard a user") {
      bobValidatorBackend.waitForInitialization()
      bobValidatorBackend.onboardUser("bob-user")
    }
    clue("Secret based onboarding endpoint is disabled") {
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        {
          intercept[com.digitalasset.canton.console.CommandFailure] {
            sv1Backend.onboardValidator(
              aliceValidatorBackend.participantClient.id.adminParty,
              "bogus-secret",
              "contact-point",
            )
          }
        },
        lines => {
          forAtLeast(1, lines) { line =>
            line.message should include(
              "Endpoint is disabled: the network has migrated to traffic-based onboarding"
            )
          }
        },
      )
    }
  }

  private def setPermissionedSynchronizerSwitchOverTime(
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val config = sv1Backend.getDsoInfo().dsoRules.payload.config
    val newConfig = new DsoRulesConfig(
      config.numUnclaimedRewardsThreshold,
      config.numMemberTrafficContractsThreshold,
      config.actionConfirmationTimeout,
      config.svOnboardingRequestTimeout,
      config.svOnboardingConfirmedTimeout,
      config.voteRequestTimeout,
      config.dsoDelegateInactiveTimeout,
      config.synchronizerNodeConfigLimits,
      config.maxTextLength,
      config.decentralizedSynchronizer,
      config.nextScheduledSynchronizerUpgrade,
      config.voteCooldownTime,
      config.nextScheduledLogicalSynchronizerUpgrade,
      Optional.of(
        (config.svOperationsSwitchOverTimes.toScala
          .map(_.asScala.toMap)
          .getOrElse(Map.empty[String, java.time.Instant]) +
          (SwitchOverTimes.PermissionedSynchronizer -> env.environment.clock.now
            .plusSeconds(10)
            .toInstant)).asJava
      ),
    )
    sv1Backend.createVoteRequest(
      sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
      new ARC_DsoRules(new SRARC_SetConfig(new DsoRules_SetConfig(newConfig, Optional.empty()))),
      "url",
      "set the permissioned-synchronizer switch-over time",
      config.voteRequestTimeout,
      None,
    )

    val voteRequest = sv1Backend.listVoteRequests().head

    Seq(sv2Backend, sv3Backend, sv4Backend).foreach { sv =>
      eventuallySucceeds() {
        sv.castVote(voteRequest.contractId, isAccepted = true, "url", "description")
      }
    }
  }
}
