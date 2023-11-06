package com.daml.network.integration.tests

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_ConfirmSvOnboarding
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.SvOnboardingStatus
import com.daml.network.sv.util.SvOnboardingToken
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.PartyId
import org.slf4j.event.Level

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random

class SvOnboardingIntegrationTest extends SvIntegrationTestBase {

  "fail registration with invalid tokens, succeed with a valid token" in { implicit env =>
    initSvc()
    sv1Backend.startSync()

    implicit val sys = env.actorSystem
    registerHttpConnectionPoolsCleanup(env)

    val registerGet = Get(s"${sv1Backend.httpClientConfig.url}/api/sv/v0/admin/authorization")

    def tokenHeader(token: String) = Seq(Authorization(OAuth2BearerToken(token)))

    val invalidSignatureToken = JWT
      .create()
      .withAudience(sv1Backend.config.auth.audience)
      .withSubject(sv1Backend.config.ledgerApiUser)
      .sign(Algorithm.HMAC256("wrong-secret"))
    val responseForInvalidSignature = Http()
      .singleRequest(registerGet.withHeaders(tokenHeader(invalidSignatureToken)))
      .futureValue
    responseForInvalidSignature.status should be(StatusCodes.Unauthorized)

    val invalidAudienceToken = JWT
      .create()
      .withAudience("wrong-audience")
      .withSubject(sv1Backend.config.ledgerApiUser)
      .sign(AuthUtil.testSignatureAlgorithm)
    val responseForInvalidAudience = Http()
      .singleRequest(registerGet.withHeaders(tokenHeader(invalidAudienceToken)))
      .futureValue
    responseForInvalidAudience.status should be(StatusCodes.Unauthorized)
    loggerFactory.assertLogs(
      {
        val invalidUserToken = JWT
          .create()
          .withAudience(sv1Backend.config.auth.audience)
          .withSubject(sv2Backend.config.ledgerApiUser)
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
        val svParty = sv1Backend.participantClientWithAdminToken.ledger_api.users
          .get(sv1Backend.config.ledgerApiUser)
          .primaryParty
          .value

        val testUser = sv1Backend.participantClientWithAdminToken.ledger_api.users.create(
          s"testUser-${Random.nextInt()}",
          actAs = Set.empty[PartyId],
          primaryParty = Some(svParty),
        )
        val userWithWrongActAs = JWT
          .create()
          .withAudience(sv1Backend.config.auth.audience)
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

    val headers = sv1Backend.headers
    val validResponse = Http()
      .singleRequest(registerGet.withHeaders(headers))
      .futureValue
    validResponse.status should be(StatusCodes.OK)

  }

  "Non-leader SVs can onboard new validators" in { implicit env =>
    initSvc()
    val sv = sv4Backend // not a leader
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
    val config =
      sv1Backend.participantClient.domains.config(sv1Backend.config.domains.global.alias).value
    bobValidatorBackend.participantClient.domains.connect(config)
    val candidate = clue("create a dummy party") {
      val name = "dummy" + env.environment.config.name.getOrElse("")
      bobValidatorBackend.participantClientWithAdminToken.ledger_api.parties
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
      eventuallySucceeds() {
        sv.onboardValidator(candidate, secret)
      }
    }
  }

  "Validator candidates can self-service at the validator onboarding tap" in { implicit env =>
    initSvc()
    val sv = sv3Backend // a random sv
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
      sv1Backend.listOngoingValidatorOnboardings() should have length 4
    }
    clue("SV1 doesn't recreate ValidatorOnboarding contracts on restart...") {
      sv1Backend.stop()
      sv1Backend.startSync()
      sv1Backend.listOngoingValidatorOnboardings() should have length 4
    }
    clue("...even if an onboarding was completed in the meantime...") {
      bobValidatorBackend.startSync()
      eventually() {
        sv1Backend.listOngoingValidatorOnboardings() should have length 3
      }
      sv1Backend.stop()
      sv1Backend.startSync()
      sv1Backend.listOngoingValidatorOnboardings() should have length 3
    }
  }

  "SVs can onboard new SVs" in { implicit env =>
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
    clue("Simulate that sv3 hasn't approved sv4 by archiving the respective `ApprovedSvIdentity`") {
      inside(
        sv3Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(
            sv3Backend.getSvcInfo().svParty,
            c => c.data.candidateName == "Canton-Foundation-4",
          )
      ) {
        case Seq(approvedSvId) => {
          sv3Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              sv3Backend.config.ledgerApiUser,
              actAs = Seq(sv3Backend.getSvcInfo().svParty),
              readAs = Seq.empty,
              update = approvedSvId.id.exerciseArchive(
                new com.daml.network.codegen.java.da.internal.template.Archive()
              ),
            )
        }
      }
    }
    clue("Stop SV2 so that SV4 can't gather enough confirmations just yet") {
      sv2Backend.stop()
      // We now need 2 confirmations to execute an action, but only sv1 is
      // active and sv3 hasn't approved sv4.
    }
    clue("SV4 starts") {
      sv4ValidatorBackend.start()
      sv4Backend.start()
    }
    val sv1Party = sv1Backend.getSvcInfo().svParty
    // We are not using sv4.getSvcInfo() to get sv4's party id
    // because the SvApp is not completely initialized yet and hence the http service is not available.
    val sv4Party = eventually() {
      sv4Backend.participantClient.ledger_api.users
        .get(sv4Backend.config.ledgerApiUser)
        .primaryParty
        .value
    }

    val (token, svOnboardingRequestCid) =
      clue("Checking that SV4's `SvOnboarding` contract was created correctly by SV1") {
        eventually()(
          // The onboarding is requested by SV4 during SvApp init.
          inside(
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
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
      sv1Backend.startSvOnboarding(token)
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) should have length 1
    }
    clue(
      "SVs that haven't approved a candidate refuse to create a `SvOnboarding` contract for it."
    ) {
      assertThrowsAndLogsCommandFailures(
        sv3Backend.startSvOnboarding(token),
        _.errorMessage should include("no matching approved SV identity found"),
      )
    }
    clue("All online and approving SVs confirm SV4's onboarding") {
      eventually() {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
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
      sv1Backend
        .getSvcInfo()
        .svcRules
        .payload
        .members
        .keySet should not contain sv4Party.toProtoPrimitive
    }
    clue("SV4's onboarding status is reported correctly.") {
      eventually()(inside(sv1Backend.getSvOnboardingStatus(sv4Party)) {
        case status: SvOnboardingStatus.Requested => {
          status.name shouldBe "Canton-Foundation-4"
          status.svOnboardingRequestCid shouldBe svOnboardingRequestCid
          status.confirmedBy.sorted shouldBe Vector("Canton-Foundation-1")
          status.requiredNumConfirmations shouldBe 2
          sv1Backend.getSvOnboardingStatus("Canton-Foundation-4") shouldBe sv1Backend
            .getSvOnboardingStatus(
              sv4Party
            )
        }
      })
    }
    actAndCheck(timeUntilSuccess = 1.minute)("SV2 comes back online", sv2Backend.start())(
      "SV4's onboarding gathers suffcient confirmations and is completed",
      { _ =>
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) shouldBe empty
        sv1Backend.getSvcInfo().svcRules.payload.members.keySet should contain(
          sv4Party.toProtoPrimitive
        )
      },
    )
    clue("SV4's onboarding status is reported as completed.") {
      eventually()(inside(sv1Backend.getSvOnboardingStatus(sv4Party)) {
        case status: SvOnboardingStatus.Completed => {
          status.name shouldBe "Canton-Foundation-4"
          status.svcRulesCid shouldBe sv1Backend.getSvcInfo().svcRules.contractId
          sv1Backend.getSvOnboardingStatus("Canton-Foundation-4") shouldBe sv1Backend
            .getSvOnboardingStatus(
              sv4Party
            )
        }
      })
    }
    sv4Backend.waitForInitialization()
    sv4ValidatorBackend.waitForInitialization()

    // we need to wait for a minute due to non sv validator only connect to sequencers after initialization + sequencerAvailabilityDelay which is is 60s
    eventually(timeUntilSuccess = 1.minutes, maxPollInterval = 1.second) {
      val membersInfoFromSvcRules = sv1Backend.getSvcInfo().svcRules.payload.members

      forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { svBackend =>
        val svParty = svBackend.getSvcInfo().svParty
        val globalDomain = svBackend.config.domains.global.alias
        val sequencerConnections = svBackend.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE

        val localSequencerUrl = inside(sequencerConnections) {
          case Seq(
                GrpcSequencerConnection(defaultSequencerEndpoint, _, _, _)
              ) =>
            defaultSequencerEndpoint.forgetNE.map(_.toURI(false)).headOption.value
          case Seq(
                GrpcSequencerConnection(_, _, _, _),
                GrpcSequencerConnection(localSequencerEndpoint, _, _, _),
              ) =>
            localSequencerEndpoint.forgetNE.map(_.toURI(false)).headOption.value
        }

        val memberInfo = membersInfoFromSvcRules.get(svParty.toProtoPrimitive)
        forAll(memberInfo.domainNodes.values()) { domainNode =>
          domainNode.sequencer.toScala.value.url shouldBe localSequencerUrl.toString
          domainNode.mediator.toScala.value.mediatorId should not be empty
        }

        clue("published sequencer information can be seen via scan") {
          inside(sv1ScanBackend.listSvcSequencers()) { case Seq(domainSequencers) =>
            domainSequencers.sequencers should have size 4
            domainSequencers.sequencers.find(s =>
              s.svName == memberInfo.name && s.url == localSequencerUrl.toString
            ) should not be empty
          }
        }
      }
    }
  }

  // remaining states are tested as part of "SVs can onboard new SVs"
  "SV onboarding status is reported correctly for `unknown` and `confirmed` states" in {
    implicit env =>
      // only 1 SV => slightly faster test
      clue("Initialize SVC with 1 SV") {
        startAllSync(sv1ScanBackend, sv1Backend)
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 1
      }
      // SV two’s party hasn't been allocated at this point because the SV app isn't running so we allocate it here.
      val (sv2Party, _) = actAndCheck(
        "allocate sv2 party",
        sv2Backend.participantClientWithAdminToken.ledger_api.parties
          .allocate(sv2Backend.config.ledgerApiUser, sv2Backend.config.ledgerApiUser)
          .party,
      )(
        "sv1 sees sv2 party",
        party =>
          sv1Backend.participantClientWithAdminToken.parties
            .list(filterParty = party.toProtoPrimitive) should not be empty,
      )

      clue("Unknown parties have unknown SV onboarding status") {
        inside(sv1Backend.getSvOnboardingStatus(sv2Party)) { case SvOnboardingStatus.Unknown() =>
          sv1Backend.getSvOnboardingStatus("Canton-Foundation-2") shouldBe sv1Backend
            .getSvOnboardingStatus(
              sv2Party
            )
        }
      }
      actAndCheck(
        "Moving sv2 to confirmed state",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = "svc",
            actAs = Seq(svcParty),
            readAs = Seq(),
            update = sv1Backend
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
          inside(sv1Backend.getSvOnboardingStatus(sv2Party)) {
            case status: SvOnboardingStatus.Confirmed => {
              status.name shouldBe "Canton-Foundation-2"
              status.svOnboardingConfirmedCid shouldBe svOnboardingConfirmedCid
              sv1Backend.getSvOnboardingStatus("Canton-Foundation-2") shouldBe sv1Backend
                .getSvOnboardingStatus(
                  sv2Party
                )
            }
          },
      )
  }

  "The new sv with same name can be onboarded and overwrite existing member only in devnet" in {
    implicit env =>
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

      val fakeSv4Party = allocateRandomSvParty("sv4")
      val coinConfig = sv1ScanBackend.getCoinConfigAsOf(env.environment.clock.now)
      actAndCheck(
        "Add a fake sv4 Party to SvcRules.members to simulate sv4 is already added to SVC", {
          sv1Backend.participantClient.ledger_api_extensions.commands.submitWithResult(
            sv1Backend.config.ledgerApiUser,
            actAs = Seq(svcParty),
            readAs = Seq.empty,
            update = sv1Backend
              .getSvcInfo()
              .svcRules
              .contractId
              .exerciseSvcRules_AddMember(
                fakeSv4Party.toProtoPrimitive,
                "Canton-Foundation-4",
                sv1Backend.participantClient.id.toProtoPrimitive,
                new Round(3),
                coinConfig.globalDomain.activeDomain,
              ),
          )
        },
      )(
        "sv4 is added as an SVC member with the fake party Id",
        _ =>
          inside(
            sv1Backend
              .getSvcInfo()
              .svcRules
              .payload
              .members
              .asScala
              .get(fakeSv4Party.toProtoPrimitive)
          ) { case Some(memberInfo) =>
            memberInfo.name shouldBe "Canton-Foundation-4"
          },
      )

      actAndCheck(
        "start sv4 with a party id different from existing sv4 in SVC", {
          startAllSync(sv4Backend, sv4ValidatorBackend)
        },
      )(
        "existing member sv4 is overwritten with different party id",
        _ => {
          inside(
            sv1Backend
              .getSvcInfo()
              .svcRules
              .payload
              .members
              .asScala
              .get(sv4Backend.getSvcInfo().svParty.toProtoPrimitive)
          ) { case Some(memberInfo) =>
            memberInfo.name shouldBe "Canton-Foundation-4"
          }
        },
      )
  }

  "The election request succeeds if one SV is onboarded in the middle of an election request" in {
    implicit env =>
      clue("Initialize SVC with 2 SVs") {
        startAllSync(
          sv1ScanBackend,
          sv1Backend,
          sv2Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
        )
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 2
      }

      val currentLeader = sv1Backend.getSvcInfo().svParty.toProtoPrimitive
      val newLeader = sv2Backend.getSvcInfo().svParty.toProtoPrimitive
      val newRanking: Vector[String] = Seq(newLeader, currentLeader).toVector

      // note that the new leader has to vote for himself to prove readiness
      actAndCheck(
        "sv2 creates a new election request for epoch 1", {
          sv2Backend
            .createElectionRequest(newLeader, newRanking)
        },
      )(
        "the epoch stays the same",
        _ => {
          sv1Backend.getSvcInfo().svcRules.payload.leader shouldBe currentLeader
        },
      )

      clue("SV3 gets onboarded") {
        startAllSync(
          sv3Backend,
          sv3ValidatorBackend,
        )
        sv1Backend.getSvcInfo().svcRules.payload.members should have size 3
        sv1Backend.getSvcInfo().svcRules.payload.epoch shouldBe 0
      }

      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
        actAndCheck(
          "sv3 creates a new election request for epoch 1", {
            val sv3 = sv3Backend.getSvcInfo().svParty.toProtoPrimitive
            sv3Backend
              .createElectionRequest(sv3, newRanking.appended(sv3))
          },
        )(
          "the epoch increased and sv2 is the new leader",
          _ => {
            sv1Backend.getSvcInfo().svcRules.payload.epoch shouldBe 1
            sv1Backend.getSvcInfo().svcRules.payload.leader shouldBe newLeader
          },
        ),
        logEntries => {
          forAtLeast(1, logEntries)(logEntry => {
            logEntry.message should startWith(
              "Noticed an SvcRules epoch change"
            )
          })
        },
      )
  }
}
