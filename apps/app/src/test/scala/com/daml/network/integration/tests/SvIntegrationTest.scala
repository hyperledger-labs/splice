package com.daml.network.integration.tests

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.sv.util.SvOnboardingToken
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SvIntegrationTest extends CoinIntegrationTest {

  private val cantonCoinDarPath =
    "daml/canton-coin/.daml/dist/canton-coin-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withManualStart

  "start and restart cleanly" in { implicit env =>
    initSvc()
    sv1.stop()
    sv1.startSync()
  }

  "The SVC is bootstrapped correctly" in { implicit env =>
    initSvc()
    val svcRules = clue("An SvcRules contract exists") { getSvcRules() }
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getDebugInfo().svParty.toProtoPrimitive)
    }
    clue("The four sv apps are all svc members and there are no other svc members") {
      svcRules.data.members.keySet should equal(svParties.toSet.asJava)
    }
    clue("The founding SV app (sv1) is the first leader") {
      getSvcRules().data.leader should equal(sv1.getDebugInfo().svParty.toProtoPrimitive)
    }
  }

  "SV parties can't act as the SVC party and can read as both themselves and the SVC party" in {
    implicit env =>
      initSvc()
      svs.foreach(sv => {
        val rights = sv.remoteParticipant.ledger_api.users.rights.list(sv.config.ledgerApiUser)
        rights.actAs should not contain (svcParty.toLf)
        rights.readAs should contain(svcParty.toLf)
      })
      actAndCheck(
        "creating a `ValidatorOnboarding` contract readable only by sv3", {
          val sv = sv3 // it doesn't really matter which sv we pick
          val svParty = sv.getDebugInfo().svParty
          sv.listOngoingValidatorOnboardings() shouldBe empty
          sv.remoteParticipant.ledger_api_extensions.commands.submitWithResult(
            sv.config.ledgerApiUser,
            actAs = Seq(svParty),
            readAs = Seq.empty,
            update = new cn.validatoronboarding.ValidatorOnboarding(
              svParty.toProtoPrimitive,
              "test",
              env.environment.clock.now.toInstant.plusSeconds(3600),
            ).create,
          )
        },
      )(
        "sv3's store ingests the contract",
        created =>
          inside(sv3.listOngoingValidatorOnboardings()) { case Seq(visible) =>
            visible.contractId shouldBe created.contractId
          },
      )
  }

  "Non-leader SVs can onboard new validators" in { implicit env =>
    initSvc()
    // Upload the DAR so validator onboarding can succeed. Usually this is done through the validator app
    // but because here we don't start one, we need to perform this step manually.
    bobValidator.remoteParticipant.dars.upload(cantonCoinDarPath)
    val sv = sv4 // not a leader
    val svParty = sv.getDebugInfo().svParty
    sv.listOngoingValidatorOnboardings() should have length 0
    val secret = actAndCheck(
      "the sv operator prepares the onboarding", {
        sv.prepareValidatorOnboarding(1.hour)
      },
    )(
      "a validator onboarding contract is created",
      { secret =>
        {
          inside(sv.listOngoingValidatorOnboardings()) { case Seq(vo) =>
            vo.payload.candidateSecret shouldBe secret
          }
        }
      },
    )._1
    val candidate = clue("create a dummy party") {
      val name = "dummy" + env.environment.config.name.getOrElse("")
      PartyId.tryFromLfParty(
        bobValidator.remoteParticipantWithAdminToken.ledger_api.parties
          .allocate(
            name,
            name,
          )
          .party
      )

    }
    clue("try to onboard with a wrong secret, which should fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "wrongsecret")
        )
      )
    }
    actAndCheck(
      "request to onboard the candidate",
      sv.onboardValidator(candidate, secret),
    )(
      "the candidate's secret is marked as used",
      Unit => {
        inside(
          svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.validatoronboarding.UsedSecret.COMPANION)(svParty)
        ) {
          case Seq(usedSecret) => {
            usedSecret.data.secret shouldBe secret
            usedSecret.data.validator shouldBe candidate.toProtoPrimitive
          }
        }
      },
    )
    clue("try to reuse the same secret for a second onboarding, which should fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "dummysecret")
        )
      )
    }
  }

  "Validator candidates can self-service at the validator onboarding tap" in { implicit env =>
    initSvc()
    val sv = sv3 // a random sv
    sv.listOngoingValidatorOnboardings() should have length 0
    actAndCheck(
      "the validator candidate requests a secret from the validator onboarding tap", {
        sv.devNetOnboardValidatorPrepare()
      },
    )(
      "a validator onboarding contract is created",
      { secret =>
        {
          inside(sv.listOngoingValidatorOnboardings()) { case Seq(vo) =>
            vo.payload.candidateSecret shouldBe secret
          }
        }
      },
    )
  }

  "SVs expect onboardings when asked to" in { implicit env =>
    initSvc()
    clue("SV2 has created as many ValidatorOnboarding contracts as it's configured to.") {
      sv2.listOngoingValidatorOnboardings() should have length 3
    }
    clue("SV2 doesn't recreate ValidatorOnboarding contracts on restart...") {
      sv2.stop()
      sv2.startSync()
      sv2.listOngoingValidatorOnboardings() should have length 3
    }
    clue("...even if an onboarding was completed in the meantime...") {
      bobValidator.startSync()
      sv2.listOngoingValidatorOnboardings() should have length 2
      sv2.stop()
      sv2.startSync()
      sv2.listOngoingValidatorOnboardings() should have length 2
    }
  }

  "SVs create approval contracts for configured approved SV identities" in { implicit env =>
    initSvc()
    clue("SV1 has created an ApprovedSvIdentity contract as it's configured to.") {
      inside(
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(sv1.getDebugInfo().svParty)
      ) {
        case Seq(approvedSvId) => {
          // if this check fails:
          // make sure that the values (especially the key) are in sync with sv1's config file
          approvedSvId.data.candidateName shouldBe "sv4"
          approvedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw=="
        }
      }
    }
  }

  "SVs create SvOnboarding contracts when legitimately requested to do so" in { implicit env =>
    initSvc()
    val sv1Party = sv1.getDebugInfo().svParty
    val sv4Party = sv4.getDebugInfo().svParty
    val token = clue("Checking that SV4's `SvOnboarding` contract was created correctly by SV1.") {
      // The onboarding is requested by SV4 during SvApp init.
      inside(
        svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboarding.COMPANION)(svcParty)
      ) {
        case Seq(svOnboarding) => {
          svOnboarding.data.candidateName shouldBe "sv4"
          svOnboarding.data.candidateParty shouldBe sv4Party.toProtoPrimitive
          svOnboarding.data.sponsor shouldBe sv1Party.toProtoPrimitive
          svOnboarding.data.svc shouldBe svcParty.toProtoPrimitive
          // if this check fails:
          // make sure that the values (especially the key) are in sync with sv1's and sv4's config files
          SvOnboardingToken
            .verifyAndDecode(svOnboarding.data.token)
            .value shouldBe SvOnboardingToken(
            "sv4",
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==",
            sv4Party,
            svcParty,
          )
          svOnboarding.data.token
        }
      }
    }
    clue("Attempting to start an onboarding multiple times has no effect.") {
      sv1.onboardSv(token)
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svonboarding.SvOnboarding.COMPANION)(svcParty) should have length 1
    }
    clue(
      "SVs that haven't approved a candidate refuse to create a `SvOnboarding` contract for it."
    ) {
      assertThrowsAndLogsCommandFailures(
        sv2.onboardSv(token),
        _.errorMessage should include("No matching approved SV identity found."),
      )
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svonboarding.SvOnboarding.COMPANION)(svcParty) should have length 1
    }
  }

  def getSvcRules()(implicit env: CoinTestConsoleEnvironment) =
    clue("There is exactly one SvcRules contract") {
      val foundSvcRules = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
      foundSvcRules should have length 1
      foundSvcRules.head
    }

  def getCoinRules()(implicit env: CoinTestConsoleEnvironment) =
    clue("There is exactly one CoinRules contract") {
      val foundCoinRules = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
      foundCoinRules should have length 1
      foundCoinRules.head
    }
}
