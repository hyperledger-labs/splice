package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn

import java.time.Duration as JavaDuration
import scala.jdk.CollectionConverters.*
import scala.util.Random

class SvTimeBasedOnboardingIntegrationTest extends SvTimeBasedIntegrationTestBase {

  "expire stale `SvOnboarding` contracts" in { implicit env =>
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
      "Add a phantom SV and stop SV3 so that SV4 can't gather enough confirmations just yet"
    ) {
      actAndCheck(
        "Add phantom Sv and stop sv3", {
          addPhantomSv()
          sv3Backend.stop()
        },
      )(
        "there are 4 members",
        _ => sv1Backend.getSvcInfo().svcRules.payload.members should have size 4,
      )
      // We now need 3 confirmations to execute an action, but only sv1 and sv2 are active.
    }
    clue("SV4 starts") {
      sv4ValidatorBackend.start()
      sv4Backend.start()
    }
    clue("An `SvOnboarding` contract is created") {
      eventually()(
        // The onboarding is requested by SV4 during SvApp init.
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) should have length 1
      )
    }
    actAndCheck("No onboarding happens for a long time", advanceTime(JavaDuration.ofHours(25)))(
      "The `SvOnboarding` contract expires and is archived",
      _ =>
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) shouldBe empty,
    )
  }

  "expire stale `SvOnboardingConfirmed` contracts" in { implicit env =>
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
    val svXParty = allocateRandomSvParty("svX")
    actAndCheck(
      "Create a new `SvOnboardingConfirmed` Contract with new party \"svX\"",
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = sv1Backend
          .getSvcInfo()
          .svcRules
          .contractId
          .exerciseSvcRules_ConfirmSvOnboarding(
            svXParty.toProtoPrimitive,
            "new random party",
            "create new `SvOnboardingConfirmed` contract",
          )
          .commands
          .asScala
          .toSeq,
      ),
    )(
      "SvX's `SvOnboardingConfirmed` contract is created'",
      _ =>
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(
            svcParty
          ) should have length 1,
    )
    actAndCheck(
      "No confirmation happens within 24h",
      advanceTime(JavaDuration.ofHours(25)),
    )(
      "The `SvOnboardingConfirmed` contract expires and is archived",
      _ =>
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(svcParty) shouldBe empty,
    )
  }

  "archive expired `ValidatorOnboarding` contracts" in { implicit env =>
    clue("Initialize SVC with 1 SV") {
      startAllSync(sv1ScanBackend, sv1Backend, sv1ValidatorBackend)
      sv1Backend.getSvcInfo().svcRules.payload.members should have size 1
    }
    val testCandidateSecret = Random.alphanumeric.take(10).mkString
    actAndCheck(
      "create a new `ValidatorOnboarding` contract", {
        val validatorOnboarding = new cn.validatoronboarding.ValidatorOnboarding(
          sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
          testCandidateSecret,
          sv1Backend.participantClientWithAdminToken.ledger_api.time
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

}
