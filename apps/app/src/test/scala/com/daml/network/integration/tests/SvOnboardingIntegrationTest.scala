package com.daml.network.integration.tests

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.codegen.java.cn.svcrules.{SvcRules, SvcRules_ConfirmSvOnboarding}
import com.daml.network.console.SvAppBackendReference
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.SvOnboardingStatus
import com.daml.network.sv.util.SvOnboardingToken
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Random

class SvOnboardingIntegrationTest extends SvIntegrationTestBase {

  "fail registration with invalid tokens, succeed with a valid token" in { implicit env =>
    initSvc()
    sv1.startSync()

    implicit val sys = env.actorSystem
    implicit val ec = env.executionContext
    CoordinatedShutdown(sys).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "cleanup") {
      () =>
        Http().shutdownAllConnectionPools().map(_ => Done)
    }

    val registerGet = Get(s"${sv1.httpClientConfig.url}/admin/authorization")

    def tokenHeader(token: String) = Seq(Authorization(OAuth2BearerToken(token)))

    val invalidSignatureToken = JWT
      .create()
      .withAudience(sv1.config.auth.audience)
      .withSubject(sv1.config.ledgerApiUser)
      .sign(Algorithm.HMAC256("wrong-secret"))
    val responseForInvalidSignature = Http()
      .singleRequest(registerGet.withHeaders(tokenHeader(invalidSignatureToken)))
      .futureValue
    responseForInvalidSignature.status should be(StatusCodes.Unauthorized)

    val invalidAudienceToken = JWT
      .create()
      .withAudience("wrong-audience")
      .withSubject(sv1.config.ledgerApiUser)
      .sign(AuthUtil.testSignatureAlgorithm)
    val responseForInvalidAudience = Http()
      .singleRequest(registerGet.withHeaders(tokenHeader(invalidAudienceToken)))
      .futureValue
    responseForInvalidAudience.status should be(StatusCodes.Unauthorized)
    loggerFactory.assertLogs(
      {
        val invalidUserToken = JWT
          .create()
          .withAudience(sv1.config.auth.audience)
          .withSubject(sv2.config.ledgerApiUser)
          .sign(AuthUtil.testSignatureAlgorithm)
        val responseForInvalidUser = Http()
          .singleRequest(registerGet.withHeaders(tokenHeader(invalidUserToken)))
          .futureValue
        responseForInvalidUser.status should be(StatusCodes.Forbidden)
        responseForInvalidUser.entity.getContentType().toString should be("application/json")
      },
      _.warningMessage should include(
        "Authorization Failed"
      ),
    )
    loggerFactory.assertLogs(
      {
        val svParty = sv1.participantClientWithAdminToken.ledger_api.users
          .get(sv1.config.ledgerApiUser)
          .primaryParty
          .value

        val testUser = sv1.participantClientWithAdminToken.ledger_api.users.create(
          s"testUser-${Random.nextInt()}",
          actAs = Set.empty[PartyId],
          primaryParty = Some(svParty),
        )
        val userWithWrongActAs = JWT
          .create()
          .withAudience(sv1.config.auth.audience)
          .withSubject(testUser.id)
          .sign(AuthUtil.testSignatureAlgorithm)
        val responseForUserWithWrongActAs =
          Http()
            .singleRequest(registerGet.withHeaders(tokenHeader(userWithWrongActAs)))
            .futureValue
        responseForUserWithWrongActAs.status should be(StatusCodes.Forbidden)
        responseForUserWithWrongActAs.entity.getContentType().toString should be("application/json")
      },
      _.warningMessage should include(
        "Authorization Failed"
      ),
    )

    val headers = sv1.headers
    val validResponse = Http()
      .singleRequest(registerGet.withHeaders(headers))
      .futureValue
    validResponse.status should be(StatusCodes.OK)

  }

  "Non-leader SVs can onboard new validators" in { implicit env =>
    initSvc()
    val sv = sv4 // not a leader
    val svParty = sv.getSvcInfo().svParty
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
    // Not starting validator so we need to connect the participant manually.
    val config = sv1.participantClient.domains.config(sv1.config.domains.global.alias).value
    bobValidator.participantClient.domains.connect(config)
    val candidate = clue("create a dummy party") {
      val name = "dummy" + env.environment.config.name.getOrElse("")
      bobValidator.participantClientWithAdminToken.ledger_api.parties
        .allocate(
          name,
          name,
        )
        .party

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
      _ => {
        inside(
          sv.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.validatoronboarding.UsedSecret.COMPANION)(svParty)
        ) {
          case Seq(usedSecret) => {
            usedSecret.data.secret shouldBe secret
            usedSecret.data.validator shouldBe candidate.toProtoPrimitive
          }
        }
      },
    )
    clue("try to onboard with a wrong secret, which should still fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "wrongsecret")
        )
      )
    }
    clue("try to reuse the same secret for a second onboarding, which should succeed") {
      eventually() {
        sv.onboardValidator(candidate, secret)
      }
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
    clue("SV1 has created as many ValidatorOnboarding contracts as it's configured to.") {
      sv1.listOngoingValidatorOnboardings() should have length 4
    }
    clue("SV1 doesn't recreate ValidatorOnboarding contracts on restart...") {
      sv1.stop()
      sv1.startSync()
      sv1.listOngoingValidatorOnboardings() should have length 4
    }
    clue("...even if an onboarding was completed in the meantime...") {
      bobValidator.startSync()
      eventually() {
        sv1.listOngoingValidatorOnboardings() should have length 3
      }
      sv1.stop()
      sv1.startSync()
      sv1.listOngoingValidatorOnboardings() should have length 3
    }
  }

  "SVs can onboard new SVs" in { implicit env =>
    clue("Initialize SVC with 3 SVs") {
      startAllSync(sv1Scan, sv1, sv2, sv3, sv1Validator, sv2Validator, sv3Validator)
      sv1.getSvcInfo().svcRules.payload.members should have size 3
    }
    clue("Simulate that sv3 hasn't approved sv4 by archiving the respective `ApprovedSvIdentity`") {
      inside(
        sv3.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(
            sv3.getSvcInfo().svParty,
            c => c.data.candidateName == "Canton-Foundation-4",
          )
      ) {
        case Seq(approvedSvId) => {
          sv3.participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
            sv3.config.ledgerApiUser,
            actAs = Seq(sv3.getSvcInfo().svParty),
            readAs = Seq.empty,
            update = approvedSvId.id.exerciseArchive(
              new com.daml.network.codegen.java.da.internal.template.Archive()
            ),
          )
        }
      }
    }
    clue("Stop SV2 so that SV4 can't gather enough confirmations just yet") {
      sv2.stop()
      // We now need 2 confirmations to execute an action, but only sv1 is
      // active and sv3 hasn't approved sv4.
    }
    clue("SV4 starts") {
      sv4Validator.start()
      sv4.start()
    }
    val sv1Party = sv1.getSvcInfo().svParty
    // We are not using sv4.getSvcInfo() to get sv4's party id
    // because the SvApp is not completely initialized yet and hence the http service is not available.
    val sv4Party = eventually() {
      sv4.participantClient.ledger_api.users.get(sv4.config.ledgerApiUser).primaryParty.value
    }

    val (token, svOnboardingRequestCid) =
      clue("Checking that SV4's `SvOnboarding` contract was created correctly by SV1") {
        eventually()(
          // The onboarding is requested by SV4 during SvApp init.
          inside(
            sv1.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty)
          ) {
            case Seq(svOnboarding) => {
              svOnboarding.data.candidateName shouldBe "Canton-Foundation-4"
              svOnboarding.data.candidateParty shouldBe sv4Party.toProtoPrimitive
              svOnboarding.data.sponsor shouldBe sv1Party.toProtoPrimitive
              svOnboarding.data.svc shouldBe svcParty.toProtoPrimitive
              // if this check fails:
              // make sure that the values (especially the key) are in sync with sv1's and sv4's config files
              SvOnboardingToken
                .verifyAndDecode(svOnboarding.data.token)
                .value shouldBe SvOnboardingToken(
                "Canton-Foundation-4",
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZMNsDJr1uTwMTIIlzUZpUexTLqVGMsD7cR4Y8sqYYFYhldVMeHG5zSubf+p+WZbLEyMUCT5nBCCBh0oiUY9crA==",
                sv4Party,
                svcParty,
              )
              (svOnboarding.data.token, svOnboarding.id)
            }
          }
        )
      }
    clue("Attempting to start an onboarding multiple times has no effect") {
      sv1.startSvOnboarding(token)
      sv1.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) should have length 1
    }
    clue(
      "SVs that haven't approved a candidate refuse to create a `SvOnboarding` contract for it."
    ) {
      assertThrowsAndLogsCommandFailures(
        sv3.startSvOnboarding(token),
        _.errorMessage should include("no matching approved SV identity found"),
      )
    }
    clue("All online and approving SVs confirm SV4's onboarding") {
      eventually() {
        sv1.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action match {
            case a: ARC_SvcRules =>
              a.svcAction match {
                case confirm: SRARC_ConfirmSvOnboarding =>
                  confirm.svcRules_ConfirmSvOnboardingValue.newMemberName == "Canton-Foundation-4"
                case _ => false
              }
            case _ => false
          }) should have length 1
      }
      sv1.getSvcInfo().svcRules.payload.members.keySet should not contain sv4Party.toProtoPrimitive
    }
    clue("SV4's onboarding status is reported correctly.") {
      eventually()(inside(sv1.getSvOnboardingStatus(sv4Party)) {
        case status: SvOnboardingStatus.Requested => {
          status.name shouldBe "Canton-Foundation-4"
          status.svOnboardingRequestCid shouldBe svOnboardingRequestCid
          status.confirmedBy.sorted shouldBe Vector("Canton-Foundation-1")
          status.requiredNumConfirmations shouldBe 2
          sv1.getSvOnboardingStatus("Canton-Foundation-4") shouldBe sv1.getSvOnboardingStatus(
            sv4Party
          )
        }
      })
    }
    actAndCheck("SV2 comes back online", sv2.start())(
      "SV4's onboarding gathers suffcient confirmations and is completed",
      { _ =>
        sv1.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) shouldBe empty
        sv1.getSvcInfo().svcRules.payload.members.keySet should contain(sv4Party.toProtoPrimitive)
      },
    )
    clue("SV4's onboarding status is reported as completed.") {
      eventually()(inside(sv1.getSvOnboardingStatus(sv4Party)) {
        case status: SvOnboardingStatus.Completed => {
          status.name shouldBe "Canton-Foundation-4"
          status.svcRulesCid shouldBe sv1.getSvcInfo().svcRules.contractId
          sv1.getSvOnboardingStatus("Canton-Foundation-4") shouldBe sv1.getSvOnboardingStatus(
            sv4Party
          )
        }
      })
    }
    sv4.waitForInitialization()
    sv4Validator.waitForInitialization()
  }

  // remaining states are tested as part of "SVs can onboard new SVs"
  "SV onboarding status is reported correctly for `unknown` and `confirmed` states" in {
    implicit env =>
      // only 1 SV => slightly faster test
      clue("Initialize SVC with 1 SV") {
        startAllSync(sv1Scan, sv1)
        sv1.getSvcInfo().svcRules.payload.members should have size 1
      }
      // SV two’s party hasn't been allocated at this point because the SV app isn't running so we allocate it here.
      val (sv2Party, _) = actAndCheck(
        "allocate sv2 party",
        sv2.participantClientWithAdminToken.ledger_api.parties
          .allocate(sv2.config.ledgerApiUser, sv2.config.ledgerApiUser)
          .party,
      )(
        "sv1 sees sv2 party",
        party =>
          sv1.participantClientWithAdminToken.parties
            .list(filterParty = party.toProtoPrimitive) should not be empty,
      )

      clue("Unknown parties have unknown SV onboarding status") {
        inside(sv1.getSvOnboardingStatus(sv2Party)) { case SvOnboardingStatus.Unknown() =>
          sv1.getSvOnboardingStatus("Canton-Foundation-2") shouldBe sv1.getSvOnboardingStatus(
            sv2Party
          )
        }
      }
      actAndCheck(
        "Moving sv2 to confirmed state",
        sv1.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = "svc",
            actAs = Seq(svcParty),
            readAs = Seq(),
            update = sv1
              .getSvcInfo()
              .svcRules
              .contractId
              .exerciseSvcRules_ConfirmSvOnboarding(
                sv2Party.toProtoPrimitive,
                "Canton-Foundation-2",
                "no reason",
              ),
          )
          .exerciseResult,
      )(
        "Confirmed SVs get told they are are confirmed",
        svOnboardingConfirmedCid =>
          inside(sv1.getSvOnboardingStatus(sv2Party)) {
            case status: SvOnboardingStatus.Confirmed => {
              status.name shouldBe "Canton-Foundation-2"
              status.svOnboardingConfirmedCid shouldBe svOnboardingConfirmedCid
              sv1.getSvOnboardingStatus("Canton-Foundation-2") shouldBe sv1.getSvOnboardingStatus(
                sv2Party
              )
            }
          },
      )
  }

  "The new sv with same name can be onboarded and overwrite existing member only in devnet" in {
    implicit env =>
      clue("Initialize SVC with 3 SVs") {
        startAllSync(sv1Scan, sv1, sv2, sv3, sv1Validator, sv2Validator, sv3Validator)
        sv1.getSvcInfo().svcRules.payload.members should have size 3
      }

      val fakeSv4Party = allocateRandomSvParty("sv4")
      val coinConfig = sv1Scan.getCoinConfigAsOf(env.environment.clock.now)
      actAndCheck(
        "Add a fake sv4 Party to SvcRules.members to simulate sv4 is already added to SVC", {
          sv1.participantClient.ledger_api_extensions.commands.submitWithResult(
            sv1.config.ledgerApiUser,
            actAs = Seq(svcParty),
            readAs = Seq.empty,
            update = sv1
              .getSvcInfo()
              .svcRules
              .contractId
              .exerciseSvcRules_AddMember(
                fakeSv4Party.toProtoPrimitive,
                "Canton-Foundation-4",
                new Round(3),
                coinConfig.globalDomain.activeDomain,
              ),
          )
        },
      )(
        "sv4 is added as an SVC member with the fake party Id",
        _ =>
          inside(
            sv1.getSvcInfo().svcRules.payload.members.asScala.get(fakeSv4Party.toProtoPrimitive)
          ) { case Some(memberInfo) =>
            memberInfo.name shouldBe "Canton-Foundation-4"
          },
      )

      actAndCheck(
        "start sv4 with a party id different from existing sv4 in SVC", {
          startAllSync(sv4, sv4Validator)
        },
      )(
        "existing member sv4 is overwritten with different party id",
        _ => {
          inside(
            sv1
              .getSvcInfo()
              .svcRules
              .payload
              .members
              .asScala
              .get(sv4.getSvcInfo().svParty.toProtoPrimitive)
          ) { case Some(memberInfo) =>
            memberInfo.name shouldBe "Canton-Foundation-4"
          }
        },
      )
  }

  "At most 4 SV confirmations are required in devnet" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    actAndCheck(
      "Add 3 phantom SVs to SVC", {
        for { i <- 1 to 3 } {
          val name = s"phantom sv$i"
          val partyId = allocateRandomSvParty(name)
          addSvMember(partyId, name)
        }
      },
    )(
      "There should be 7 SVC members in total now",
      _ => {
        sv1.getSvcInfo().svcRules.payload.members should have size 7
      },
    )

    actAndCheck(
      "SV1 to SV4 create confirmation to Confirm SVX", {
        val svcRules = sv1.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
          .head
        val newMemberName = "Canton-Foundation-X"
        val newMemberPartyId = allocateRandomSvParty(newMemberName)
        createSvOnboardingConfirmation(svcRules, sv1, newMemberPartyId, newMemberName)
        createSvOnboardingConfirmation(svcRules, sv2, newMemberPartyId, newMemberName)
        createSvOnboardingConfirmation(svcRules, sv3, newMemberPartyId, newMemberName)
        createSvOnboardingConfirmation(svcRules, sv4, newMemberPartyId, newMemberName)
      },
    )(
      "There are 7 SVC members in total but only 4 confirmations are required to confirm a SV",
      _ =>
        inside(
          sv1.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(svcParty)
        ) { case Seq(svOnboardingConfirmed) =>
          svOnboardingConfirmed.data.svName shouldBe "Canton-Foundation-X"
        },
    )
  }

  private def createSvOnboardingConfirmation(
      svcRules: SvcRules.Contract,
      svApp: SvAppBackendReference,
      newMemberParty: PartyId,
      newMemberName: String,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val svParty = svApp.getSvcInfo().svParty
    svApp.participantClient.ledger_api_extensions.commands.submitWithResult(
      svApp.config.ledgerApiUser,
      actAs = Seq(svParty),
      readAs = Seq(svcParty),
      update = svcRules.id.exerciseSvcRules_ConfirmAction(
        svParty.toProtoPrimitive,
        new ARC_SvcRules(
          new SRARC_ConfirmSvOnboarding(
            new SvcRules_ConfirmSvOnboarding(
              newMemberParty.toProtoPrimitive,
              newMemberName,
              "because",
            )
          )
        ),
      ),
    )
  }
}
