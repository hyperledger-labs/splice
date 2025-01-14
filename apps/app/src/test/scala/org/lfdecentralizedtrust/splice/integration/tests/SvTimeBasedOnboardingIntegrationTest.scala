package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_ConfirmSvOnboarding,
  SRARC_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRulesConfig,
  DsoRules_ConfirmSvOnboarding,
  DsoRules_SetConfig,
}
import org.lfdecentralizedtrust.splice.console.AppBackendReference
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.SvOnboardingRequestTrigger
import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ExpireValidatorOnboardingTrigger
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil

import java.time.Duration as JavaDuration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Random

class SvTimeBasedOnboardingIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment
    with TriggerTestUtil {
  "expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts" in {
    implicit env =>
      implicit val ec = env.executionContext
      def activeSvBackends = Seq(sv1Backend, sv2Backend, sv3Backend)
      clue("Initialize DSO with 3 SVs") {
        startAllSync(
          Seq[AppBackendReference](sv1ScanBackend, sv2ScanBackend) ++
            activeSvBackends ++
            Seq(
              sv1ValidatorBackend,
              sv2ValidatorBackend,
              sv3ValidatorBackend,
            ): _*
        )
        sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 3
      }

      clue(
        "expire stale `SvOnboardingRequest` contracts."
      ) {
        val sv2and3OnboardingRequestTriggers =
          Seq(sv2Backend, sv3Backend).map(_.dsoAutomation.trigger[SvOnboardingRequestTrigger])

        sv2and3OnboardingRequestTriggers.traverse(_.pause()).futureValue
        // We now need 2 confirmations to execute an action, but only sv1 will confirm to onboard sv4.
        clue("SV4 starts") {
          sv4ValidatorBackend.start()
          sv4Backend.start()
        }
        clue("An `SvOnboardingRequest` contract is created") {
          // Increased timeout, because SV4 takes a while to start up
          eventually(timeUntilSuccess = 60.seconds)(
            // The onboarding is requested by SV4 during SvApp init.
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(splice.svonboarding.SvOnboardingRequest.COMPANION)(
                dsoParty
              ) should have length 1
          )
        }
        actAndCheck(
          "No onboarding happens for a long time",
          advanceTime(JavaDuration.ofHours(25)),
        )(
          "The `SvOnboardingRequest` contract expires and is archived",
          _ =>
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(splice.svonboarding.SvOnboardingRequest.COMPANION)(
                dsoParty
              ) shouldBe empty,
        )
      }

      clue(
        "expire stale `SvOnboardingConfirmed` contracts."
      ) {
        val svYParty = allocateRandomSvParty("svY")
        actAndCheck(
          "Create a new `SvOnboardingConfirmed` Contract with new party \"svY\"", {
            val confirmingSvs = getConfirmingSvs(Seq(sv1Backend, sv2Backend, sv3Backend))
            confirmActionByAllSvs(
              confirmingSvs,
              new dsorules.actionrequiringconfirmation.ARC_DsoRules(
                new SRARC_ConfirmSvOnboarding(
                  new DsoRules_ConfirmSvOnboarding(
                    svYParty.toProtoPrimitive,
                    "new random party",
                    "PAR::sv::1220f3e2",
                    SvUtil.DefaultSV1Weight,
                    "create new `SvOnboardingConfirmed` contract",
                  )
                )
              ),
            )
          },
        )(
          "SvY's `SvOnboardingConfirmed` contract is created'",
          _ =>
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(splice.svonboarding.SvOnboardingConfirmed.COMPANION)(
                dsoParty,
                _.data.svParty == svYParty.toProtoPrimitive,
              ) should have length 1,
        )
        actAndCheck(
          "No confirmation happens within 24h",
          advanceTime(JavaDuration.ofHours(25)),
        )(
          "The `SvOnboardingConfirmed` contract expires and is archived",
          _ =>
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(splice.svonboarding.SvOnboardingConfirmed.COMPANION)(
                dsoParty,
                _.data.svParty == svYParty.toProtoPrimitive,
              ) shouldBe empty,
        )
      }

      setTriggersWithin(
        Seq.empty,
        triggersToResumeAtStart = activeSvBackends.map(
          _.appState.svAutomation.trigger[ExpireValidatorOnboardingTrigger]
        ),
      ) {
        clue("archive expired `ValidatorOnboarding` contracts") {
          val testCandidateSecret = Random.alphanumeric.take(10).mkString
          actAndCheck(
            "create a new `ValidatorOnboarding` contract", {
              val validatorOnboarding = new splice.validatoronboarding.ValidatorOnboarding(
                sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
                testCandidateSecret,
                sv1Backend.participantClientWithAdminToken.ledger_api.time
                  .get()
                  .toInstant
                  .plusSeconds(3600),
              ).create.commands.asScala.toSeq

              sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
                actAs = Seq(sv1Backend.getDsoInfo().svParty),
                optTimeout = None,
                commands = validatorOnboarding,
              )
            },
          )(
            "The `ValidatorOnboarding` contract exists.",
            _ =>
              sv1Backend
                .listOngoingValidatorOnboardings()
                .filter(e =>
                  e.contract.payload.candidateSecret == testCandidateSecret
                ) should have size 1,
          )
          actAndCheck(
            "No confirmation happens within 2h",
            advanceTime(JavaDuration.ofHours(2)),
          )(
            "The `ValidatorOnboarding` contract expires and is archived",
            _ =>
              sv1Backend
                .listOngoingValidatorOnboardings()
                .filter(e =>
                  e.contract.payload.candidateSecret == testCandidateSecret
                ) should have size 0,
          )
        }
      }

      clue("archive expired `VoteRequest` contracts") {
        actAndCheck(
          "sv1 creates a new vote request", {
            val newConfig = new DsoRulesConfig(
              sv1Backend.getDsoInfo().dsoRules.payload.config.numUnclaimedRewardsThreshold,
              sv1Backend.getDsoInfo().dsoRules.payload.config.numMemberTrafficContractsThreshold,
              sv1Backend.getDsoInfo().dsoRules.payload.config.actionConfirmationTimeout,
              sv1Backend.getDsoInfo().dsoRules.payload.config.svOnboardingRequestTimeout,
              sv1Backend.getDsoInfo().dsoRules.payload.config.svOnboardingConfirmedTimeout,
              sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
              sv1Backend.getDsoInfo().dsoRules.payload.config.dsoDelegateInactiveTimeout,
              sv1Backend.getDsoInfo().dsoRules.payload.config.synchronizerNodeConfigLimits,
              sv1Backend.getDsoInfo().dsoRules.payload.config.maxTextLength,
              sv1Backend.getDsoInfo().dsoRules.payload.config.decentralizedSynchronizer,
              sv1Backend.getDsoInfo().dsoRules.payload.config.nextScheduledSynchronizerUpgrade,
            )

            val action: ActionRequiringConfirmation =
              new ARC_DsoRules(new SRARC_SetConfig(new DsoRules_SetConfig(newConfig)))

            sv1Backend.createVoteRequest(
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              action,
              "url",
              "description",
              sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
            )
          },
        )(
          "sv1 can see the new vote request",
          _ => {
            sv1Backend.listVoteRequests() should not be empty
          },
        )

        actAndCheck("one week has passed", advanceTime(JavaDuration.ofDays(8)))(
          "the vote request is not displayed anymore",
          _ => {
            sv1Backend.listVoteRequests() shouldBe empty
          },
        )
      }
  }
}
