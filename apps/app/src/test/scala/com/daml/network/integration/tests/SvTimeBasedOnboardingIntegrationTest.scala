package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.{
  SRARC_ConfirmSvOnboarding,
  SRARC_SetConfig,
}
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRulesConfig,
  SvcRules_ConfirmSvOnboarding,
  SvcRules_SetConfig,
}
import com.daml.network.sv.automation.confirmation.SvOnboardingRequestTrigger
import cats.syntax.traverse.*
import com.daml.network.codegen.java.cn.svcrules
import com.daml.network.sv.util.SvUtil.dummySvRewardWeight

import java.time.Duration as JavaDuration
import scala.jdk.CollectionConverters.*
import scala.util.Random

class SvTimeBasedOnboardingIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment {
  "expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts" in {
    implicit env =>
      implicit val ec = env.executionContext
      clue("Initialize SVC with 3 SVs") {
        startAllSync(
          sv1ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
          sv3ValidatorBackend,
        )
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 3
      }

      clue(
        "expire stale `SvOnboardingRequest` contracts."
      ) {
        val sv2and3OnboardingRequestTriggers =
          Seq(sv2Backend, sv3Backend).map(_.svcAutomation.trigger[SvOnboardingRequestTrigger])

        sv2and3OnboardingRequestTriggers.traverse(_.pause()).futureValue
        // We now need 2 confirmations to execute an action, but only sv1 will confirm to onboard sv4.
        clue("SV4 starts") {
          sv4ValidatorBackend.start()
          sv4Backend.start()
        }
        clue("An `SvOnboardingRequest` contract is created") {
          eventually()(
            // The onboarding is requested by SV4 during SvApp init.
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(
                svcParty
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
              .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) shouldBe empty,
        )
      }

      clue(
        "expire stale `SvOnboardingConfirmed` contracts."
      ) {
        val svYParty = allocateRandomSvParty("svY")
        actAndCheck(
          "Create a new `SvOnboardingConfirmed` Contract with new party \"svY\"", {
            val confirmingSvs = getConfirmingSvs(Seq(sv1Backend, sv2Backend, sv3Backend))
            confirmActionByAllMembers(
              confirmingSvs,
              new svcrules.actionrequiringconfirmation.ARC_SvcRules(
                new SRARC_ConfirmSvOnboarding(
                  new SvcRules_ConfirmSvOnboarding(
                    svYParty.toProtoPrimitive,
                    "new random party",
                    "PAR::sv::1220f3e2",
                    dummySvRewardWeight,
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
              .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(
                svcParty,
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
              .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(
                svcParty,
                _.data.svParty == svYParty.toProtoPrimitive,
              ) shouldBe empty,
        )
      }

      clue("archive expired `ValidatorOnboarding` contracts") {
        val testCandidateSecret = Random.alphanumeric.take(10).mkString
        actAndCheck(
          "create a new `ValidatorOnboarding` contract", {
            val validatorOnboarding = new cn.validatoronboarding.ValidatorOnboarding(
              sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
              testCandidateSecret,
              sv1Backend.participantClientWithAdminToken.ledger_api_v2.time
                .get()
                .toInstant
                .plusSeconds(3600),
            ).create.commands.asScala.toSeq

            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
              actAs = Seq(sv1Backend.getSvcInfo().svParty),
              optTimeout = None,
              commands = validatorOnboarding,
            )
          },
        )(
          "The `ValidatorOnboarding` contract exists.",
          _ =>
            sv1Backend
              .listOngoingValidatorOnboardings()
              .filter(e => e.payload.candidateSecret == testCandidateSecret) should have size 1,
        )
        actAndCheck(
          "No confirmation happens within 2h",
          advanceTime(JavaDuration.ofHours(2)),
        )(
          "The `ValidatorOnboarding` contract expires and is archived",
          _ =>
            sv1Backend
              .listOngoingValidatorOnboardings()
              .filter(e => e.payload.candidateSecret == testCandidateSecret) should have size 0,
        )
      }

      clue("archive expired `VoteRequest` contracts") {
        actAndCheck(
          "sv1 creates a new vote request", {
            val newConfig = new SvcRulesConfig(
              sv1Backend.getSvcInfo().svcRules.payload.config.numUnclaimedRewardsThreshold,
              sv1Backend.getSvcInfo().svcRules.payload.config.numMemberTrafficContractsThreshold,
              sv1Backend.getSvcInfo().svcRules.payload.config.actionConfirmationTimeout,
              sv1Backend.getSvcInfo().svcRules.payload.config.svOnboardingRequestTimeout,
              sv1Backend.getSvcInfo().svcRules.payload.config.svOnboardingConfirmedTimeout,
              sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
              sv1Backend.getSvcInfo().svcRules.payload.config.leaderInactiveTimeout,
              sv1Backend.getSvcInfo().svcRules.payload.config.domainNodeConfigLimits,
              sv1Backend.getSvcInfo().svcRules.payload.config.maxTextLength,
              sv1Backend.getSvcInfo().svcRules.payload.config.initialTrafficGrant,
              sv1Backend.getSvcInfo().svcRules.payload.config.svChallengeDeadline,
              sv1Backend.getSvcInfo().svcRules.payload.config.globalDomain,
              sv1Backend.getSvcInfo().svcRules.payload.config.nextScheduledDomainUpgrade,
            )

            val action: ActionRequiringConfirmation =
              new ARC_SvcRules(new SRARC_SetConfig(new SvcRules_SetConfig(newConfig)))

            sv1Backend.createVoteRequest(
              sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
              action,
              "url",
              "description",
              sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
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
