package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_OffboardSv,
  DsoRules_RepermissionValidator,
  DsoRules_UnpermissionValidator,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_OffboardSv,
  SRARC_RepermissionValidator,
  SRARC_UnpermissionValidator,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.SynchronizerPermissionState
import org.lfdecentralizedtrust.splice.util.*

import java.time.Instant
import java.util.Optional
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorrepermission.ValidatorRepermission
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorunpermission.ValidatorUnpermission
import org.lfdecentralizedtrust.splice.integration.plugins.TokenStandardCliSanityCheckPlugin

class PermissionedSynchronizerIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TokenStandardV2TestUtil
    with SynchronizerFeesTestUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected lazy val tokenStandardCliBehavior
      : TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior =
    TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior.IgnoreAll

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(foundDso => {
          val currentTimes = foundDso.initialSvOperationsSwitchOverTimes.getOrElse(Map.empty)
          foundDso.copy(
            initialSvOperationsSwitchOverTimes = Some(
              currentTimes + (
                SwitchOverTimes.PermissionedSynchronizer -> com.digitalasset.canton.data.CantonTimestamp.MinValue
              )
            )
          )
        })(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "bobValidator" =>
            c.copy(enableInternalAutoOnboardingThroughSvTrafficFaucet = true)
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "Onboard network in RestrictedOpen mode" in { implicit env =>
    initDso()

    clue(
      "Initially scan doesn't find ParticipantSynchronizerPermission for aliceValidatorBackend"
    ) {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          aliceValidatorBackend.participantClient.id.toProtoPrimitive,
        ) shouldBe None
      }
    }

    clue("Start Alice validator") {
      aliceValidatorBackend.start()
    }

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

    clue(s"SV1 buys MemberTraffic for ${aliceValidatorBackend.participantClient.name}") {
      createBuyTrafficRequest(
        validatorApp = sv1ValidatorBackend,
        buyer = sv1WalletUserParty,
        memberId = aliceValidatorBackend.participantClient.id.toProtoPrimitive,
        trafficAmount = trafficAmount,
        trackingId = s"traffic-for-${aliceValidatorBackend.participantClient.name}",
      )
    }

    val allValidators = Seq(
      sv2ValidatorBackend,
      sv3ValidatorBackend,
      sv4ValidatorBackend,
      aliceValidatorBackend,
    )

    clue("Verify all participants are granted ParticipantSynchronizerPermission") {
      allValidators.foreach { targetApp =>
        eventually() {
          sv1ScanBackend.getParticipantSynchronizerPermission(
            decentralizedSynchronizerId.toProtoPrimitive,
            targetApp.participantClient.id.toProtoPrimitive,
          ) shouldBe Some(
            SynchronizerPermissionState(None)
          )
        }
      }
    }

    actAndCheck(
      "Wait for Alice validator to bootstrap",
      aliceValidatorBackend.waitForInitialization(),
    )(
      "Onboard Alice test user",
      _ => {
        aliceValidatorBackend.onboardUser("TestUser")
      },
    )

    clue("Bob Validator starts and onboards correctly ") {
      bobValidatorBackend.startSync()
      bobValidatorBackend.onboardUser("TestUserBob")
    }

    clue("Bob Validator has ParticipantSynchronizerPermission") {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobValidatorBackend.participantClient.id.toProtoPrimitive,
        ) shouldBe Some(
          SynchronizerPermissionState(None)
        )
      }
    }

    val bobParticipantId = bobValidatorBackend.participantClient.id.toProtoPrimitive
    val suspendTime = env.environment.clock.now.plus(java.time.Duration.ofHours(1)).toInstant

    bobValidatorBackend.stop()
    aliceValidatorBackend.stop()

    clue("SVs vote to temporarily suspend Bob") {
      manuallyUnpermissionValidator(bobParticipantId, Some(suspendTime), revoked = false)
    }

    clue("Verify Bob's ParticipantSynchronizerPermission is updated with loginAfter") {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobParticipantId,
        ) shouldBe Some(
          SynchronizerPermissionState(Some(CantonTimestamp.assertFromInstant(suspendTime)))
        )
      }
    }
    clue("SVs vote to permanently revoke Bob") {
      manuallyUnpermissionValidator(bobParticipantId, None, revoked = true)
    }

    clue("Verify Bob's ParticipantSynchronizerPermission is completely removed") {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobParticipantId,
        ) shouldBe None
      }
    }

    clue("Wait for SV automation to merge Bob's ValidatorUnpermission contracts") {
      eventually() {
        sv1Backend.listValidatorUnpermissions(bobParticipantId) should have size 1
      }
    }

    clue("SVs vote to repermission Bob") {
      manuallyRepermissionValidator(
        sv1Backend.listValidatorUnpermissions(bobParticipantId).head.contractId
      )
    }

    clue("Verify Bob's ParticipantSynchronizerPermission is restored by automation") {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          bobParticipantId,
        ) shouldBe Some(SynchronizerPermissionState(None))
      }
    }

    clue("Verify ValidatorRepermission contract is archived by automation") {
      eventually() {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorRepermission.COMPANION)(
            sv1Backend.getDsoInfo().dsoParty,
            co => co.data.participantId == bobParticipantId,
          ) shouldBe empty
      }
    }

    clue("Verify Bob can operate again by onboarding a user") {
      bobValidatorBackend.startSync()
      bobValidatorBackend.onboardUser("TestUserBob2")
    }

    def manuallyUnpermissionValidator(
        participantId: String,
        loginAfter: Option[Instant],
        revoked: Boolean,
    ): Unit = {
      val action = new ARC_DsoRules(
        new SRARC_UnpermissionValidator(
          new DsoRules_UnpermissionValidator(
            participantId,
            loginAfter.map(Optional.of(_)).getOrElse(Optional.empty()),
            java.lang.Boolean.valueOf(revoked),
          )
        )
      )

      val (_, voteRequest) = actAndCheck(
        s"SV1 creates vote request to unpermission $participantId (revoked=$revoked)",
        eventuallySucceeds() {
          sv1Backend.createVoteRequest(
            sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            action,
            "url",
            "description",
            sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
            None,
          )
        },
      )(
        "vote request has been created",
        _ => sv1Backend.listVoteRequests().filter(_.payload.action == action).head,
      )

      Seq(sv2Backend, sv3Backend).foreach { sv =>
        clue(s"${sv.participantClient.name} accepts the vote request") {
          eventuallySucceeds() {
            sv.castVote(
              voteRequest.contractId,
              isAccepted = true,
              "url",
              "description",
            )
          }
        }
      }
    }

    def manuallyRepermissionValidator(
        unpermissionCid: ValidatorUnpermission.ContractId
    ): Unit = {
      val action = new ARC_DsoRules(
        new SRARC_RepermissionValidator(
          new DsoRules_RepermissionValidator(unpermissionCid)
        )
      )

      val (_, voteRequest) = actAndCheck(
        s"SV1 creates vote request to repermission validator",
        eventuallySucceeds() {
          sv1Backend.createVoteRequest(
            sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            action,
            "url",
            "description",
            sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
            None,
          )
        },
      )(
        "vote request has been created",
        _ => sv1Backend.listVoteRequests().filter(_.payload.action == action).head,
      )

      Seq(sv2Backend, sv3Backend).foreach { sv =>
        clue(s"${sv.participantClient.name} accepts the vote request") {
          eventuallySucceeds() {
            sv.castVote(
              voteRequest.contractId,
              isAccepted = true,
              "url",
              "description",
            )
          }
        }
      }
    }

    def offboardSv(svParty: String): Unit = {
      val action = new ARC_DsoRules(
        new SRARC_OffboardSv(
          new DsoRules_OffboardSv(svParty)
        )
      )

      val (_, voteRequest) = actAndCheck(
        s"SV1 creates vote request to offboard $svParty",
        eventuallySucceeds() {
          sv1Backend.createVoteRequest(
            sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            action,
            "url",
            "description",
            sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
            None,
          )
        },
      )(
        "vote request has been created",
        _ => sv1Backend.listVoteRequests().filter(_.payload.action == action).head,
      )

      Seq(sv3Backend, sv4Backend).foreach { sv =>
        clue(s"${sv.participantClient.name} accepts the vote request") {
          eventuallySucceeds() {
            sv.castVote(
              voteRequest.contractId,
              isAccepted = true,
              "url",
              "description",
            )
          }
        }
      }
    }

    val sv2ParticipantId = sv2Backend.participantClient.id.toProtoPrimitive
    val sv2PartyId = sv2Backend.getDsoInfo().svParty.toProtoPrimitive

    clue("Stop SV2") {
      sv2ScanBackend.stop()
      sv2Backend.stop()
      sv2ValidatorBackend.stop()
    }

    clue("SVs vote to offboard SV2") {
      offboardSv(sv2PartyId)
    }

    clue("Verify SV2's ParticipantSynchronizerPermission is removed") {
      eventually() {
        sv1ScanBackend.getParticipantSynchronizerPermission(
          decentralizedSynchronizerId.toProtoPrimitive,
          sv2ParticipantId,
        ) shouldBe None
      }
    }

  }
}
