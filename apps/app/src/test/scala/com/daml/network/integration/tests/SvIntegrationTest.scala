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
import com.daml.network.codegen.java.cn.svcrules.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.{
  SRARC_ConfirmSvOnboarding,
  SRARC_RemoveMember,
  SRARC_SetConfig,
}
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.console.{
  CNParticipantClientReference,
  ScanAppBackendReference,
  SvAppBackendReference,
  ValidatorAppBackendReference,
}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.SvOnboardingStatus
import com.daml.network.sv.util.SvOnboardingToken
import com.daml.network.util.{Codec, SvTestUtil}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.transaction.TopologyChangeOpX

import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random

class SvIntegrationTest extends CNNodeIntegrationTest with SvTestUtil {

  private val cantonCoinDarPath =
    "daml/canton-coin/.daml/dist/canton-coin-0.1.0.dar"
  private val svcGovernanceDarPath =
    "daml/svc-governance/.daml/dist/svc-governance-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyX(this.getClass.getSimpleName)
      .withManualStart
      .withAdditionalSetup(implicit env => {
        // Some tests rely on those DARs being present without starting the SV/validator app which usually upload these.
        sv2.participantClient.upload_dar_unless_exists(svcGovernanceDarPath)
        bobValidator.participantClient.upload_dar_unless_exists(cantonCoinDarPath)
      })

  "start and restart cleanly" in { implicit env =>
    initSvc()
    sv1.stop()
    sv1.startSync()
  }

  "can start before its validator app" in { implicit env =>
    // we want this so we can have a clear init dependency validator app -> sv app
    clue("Starting founding SV's SV app") {
      sv1.startSync()
    }
    clue("Starting founding SV's scan app") {
      // validators need this
      sv1Scan.startSync()
    }
    clue("Starting founding SV's validator app") {
      sv1Validator.startSync()
    }
    clue("Starting joining SV's SV app") {
      sv2.startSync()
    }
    clue("Starting joining SV's validator app") {
      sv2Validator.startSync()
    }
  }

  "connect to all domains during initialization" in { implicit env =>
    initSvc()
    sv4.stop()

    val globalDomainId = inside(sv4.participantClient.domains.list_connected()) {
      case Seq(domain) =>
        domain.domainId
    }

    clue("simulate the domain was left disconnected when error occur during party migration.") {
      sv4.participantClient.domains.disconnect_all()
      sv4.participantClient.domains.list_connected() shouldBe empty
    }

    clue("sv will connect to all domains during initialization.") {
      sv4.startSync()
      inside(sv4.participantClient.domains.list_connected()) {
        case Seq(listConnectedDomainsResult) =>
          listConnectedDomainsResult.domainId shouldBe globalDomainId
      }
    }
  }

  // A test to make debugging bootstrap problems easier
  "SV apps can start one by one" in { implicit env =>
    clue("Starting SVC app and SV1 app") {
      startAllSync(sv1Scan, sv1Validator, sv1)
    }
    def startSv(
        number: Int,
        sv: SvAppBackendReference,
        validator: ValidatorAppBackendReference,
        scanApp: Option[ScanAppBackendReference] = None,
    ) =
      clue(s"Starting SV$number app") {
        validator.start()
        sv.start()
        scanApp.foreach(_.start())
        validator.waitForInitialization()
        sv.waitForInitialization()
        scanApp.foreach(_.waitForInitialization())
      }

    startSv(2, sv2, sv2Validator, Some(sv2Scan))
    startSv(3, sv3, sv3Validator)
    startSv(4, sv4, sv4Validator)
  }

  "The SVC is bootstrapped correctly" in { implicit env =>
    initSvc()
    val svcRules = clue("An SvcRules contract exists") { sv1.getSvcInfo() }
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getSvcInfo().svParty.toProtoPrimitive)
    }
    clue("The four sv apps are all svc members and there are no other svc members") {
      sv1.getSvcInfo().svcRules.payload.members.keySet() should equal(svParties.toSet.asJava)
    }
    clue("The founding SV app (sv1) is the first leader") {
      sv1.getSvcInfo().svcRules.payload.leader should equal(svcRules.svParty.toProtoPrimitive)
    }
    clue("initial open mining rounds are created") {
      eventually() {
        sv1.listOpenMiningRounds() should have size 3
        sv1Scan.getOpenAndIssuingMiningRounds()._1 should have size 3
        sv2Scan.getOpenAndIssuingMiningRounds()._1 should have size 3
      }
    }
  }

  "SV users can act as SV party and act or read as the SVC party" in { implicit env =>
    initSvc()
    val rights = sv1.participantClient.ledger_api.users.rights.list(sv1.config.ledgerApiUser)
    rights.actAs should contain(svcParty)
    rights.readAs shouldBe empty
    Seq(sv2, sv3, sv4).foreach(sv => {
      val rights = sv.participantClient.ledger_api.users.rights.list(sv.config.ledgerApiUser)
      rights.actAs should not contain svcParty
      rights.readAs should contain(svcParty)
    })
    actAndCheck(
      "creating a `ValidatorOnboarding` contract readable only by sv3", {
        val sv = sv3 // it doesn't really matter which sv we pick
        val svParty = sv.getSvcInfo().svParty
        sv.listOngoingValidatorOnboardings() shouldBe empty
        sv.participantClient.ledger_api_extensions.commands.submitWithResult(
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

  "SV Identity can be approved at runtime" in { implicit env =>
    initSvc()
    svc.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(
        sv1.getSvcInfo().svParty
      ) should have length 3
    val svXName = "Canton-Foundation-X"
    val svXKey =
      "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEj6n2u5RWQdkq2cWvStGbIBe2JmoFs+vZGOVfd6oIm/FqfK2qV2fiHX9DieJ1c6BarDdsAD7IRnksD9BGisU3ZQ=="
    sv1.approveSvIdentity(svXName, svXKey)
    inside(
      svc.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(sv1.getSvcInfo().svParty)
    ) {
      case approvedSvIds => {
        approvedSvIds should have size 4
        val maybeSvXApprovedSvId = approvedSvIds.find(_.data.candidateName == svXName)
        inside(maybeSvXApprovedSvId) { case Some(svXApprovedSvId) =>
          svXApprovedSvId.data.candidateKey shouldBe svXKey
        }
      }
    }
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

  "SVs create approval contracts for configured approved SV identities" in { implicit env =>
    initSvc()
    clue("SV1 has created an ApprovedSvIdentity contract as it's configured to.") {
      inside(
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.ApprovedSvIdentity.COMPANION)(sv1.getSvcInfo().svParty)
      ) {
        case approvedSvIds => {
          // if this check fails:
          // make sure that the values (especially the key) are in sync with sv1's config file
          approvedSvIds should have size 3
          val maybeSv2ApprovedSvId =
            approvedSvIds.find(_.data.candidateName == "Canton-Foundation-2")
          inside(maybeSv2ApprovedSvId) { case Some(sv2ApprovedSvId) =>
            sv2ApprovedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEVdt8tLAfv+6H6s6EGpYMbthSdtEbykUO2Fau0k2wipf/6C0A/+xzKtqKJlBkybcBiICG/ZonGkuKgWBAC1jVAg=="
          }
          val maybeSv3ApprovedSvId =
            approvedSvIds.find(_.data.candidateName == "Canton-Foundation-3")
          inside(maybeSv3ApprovedSvId) { case Some(sv2ApprovedSvId) =>
            sv2ApprovedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7sHQDYkVisVznuFqvjWWxH3u8S+f07f1HCZ+mx+yj28ysRJjbatPNnsVAbiFDu2XOqyITx+os/Gd39piOfyw2w=="
          }
          val maybeSv4ApprovedSvId =
            approvedSvIds.find(_.data.candidateName == "Canton-Foundation-4")
          inside(maybeSv4ApprovedSvId) { case Some(sv4ApprovedSvId) =>
            sv4ApprovedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZMNsDJr1uTwMTIIlzUZpUexTLqVGMsD7cR4Y8sqYYFYhldVMeHG5zSubf+p+WZbLEyMUCT5nBCCBh0oiUY9crA=="
          }
        }
      }
    }
  }

  "SVs can onboard new SVs" in { implicit env =>
    clue("Initialize SVC with 3 SVs") {
      startAllSync(svc, sv1Scan, sv1, sv2, sv3, sv1Validator, sv2Validator, sv3Validator)
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
            svc.participantClientWithAdminToken.ledger_api_extensions.acs
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
      svc.participantClientWithAdminToken.ledger_api_extensions.acs
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
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
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
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
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
  }

  // remaining states are tested as part of "SVs can onboard new SVs"
  "SV onboarding status is reported correctly for `unknown` and `confirmed` states" in {
    implicit env =>
      // only 1 SV => slightly faster test
      clue("Initialize SVC with 1 SV") {
        startAllSync(svc, sv1Scan, sv1)
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
          sv1.participantClientWithAdminToken.participantX.parties
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
        svc.participantClientWithAdminToken.ledger_api_extensions.commands
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
        startAllSync(svc, sv1Scan, sv1, sv2, sv3, sv1Validator, sv2Validator, sv3Validator)
        sv1.getSvcInfo().svcRules.payload.members should have size 3
      }

      val fakeSv4Party = allocateRandomSvParty("sv4")
      actAndCheck(
        "Add a fake sv4 Party to SvcRules.members to simulate sv4 is already added to SVC", {
          svc.participantClient.ledger_api_extensions.commands.submitWithResult(
            svc.config.svUser,
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
        val svcRules = svc.participantClientWithAdminToken.ledger_api_extensions.acs
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
          svc.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(svcParty)
        ) { case Seq(svOnboardingConfirmed) =>
          svOnboardingConfirmed.data.svName shouldBe "Canton-Foundation-X"
        },
    )
  }

  "The SVC Party can be setup in the participant after SV has been confirmed to be part of the SVC" in {
    implicit env =>
      clue("Starting SVC app and SV1 app") {
        startAllSync(sv1Scan, sv1)
      }

      val svcParty = sv1.getSvcInfo().svcParty
      val svcPartyStr: String = svcParty.toProtoPrimitive
      val svcParticipant = sv1.participantClient
      val sv4Participant = sv4.participantClient

      clue(
        "svc party hosting authorization request with party which is not confirmed will be rejected by sponsor SV"
      ) {
        val randomParty = allocateRandomSvParty("random")
        assertThrowsAndLogsCommandFailures(
          sv1.onboardSvPartyMigrationAuthorize(
            sv4.participantClient.participantX.id,
            randomParty,
          ),
          _.errorMessage should include(
            "Candidate party is not a member and no `SvOnboardingConfirmed` for the candidate party is found."
          ),
        )
      }

      clue(
        "svc party hosting authorization request with party which is not hosted on the target participant"
      ) {
        val sv1Party = sv1.getSvcInfo().svParty
        assertThrowsAndLogsCommandFailures(
          sv1.onboardSvPartyMigrationAuthorize(
            sv4.participantClient.participantX.id,
            sv1Party,
          ),
          _.errorMessage should include(
            s"Candidate party $sv1Party is not authorized by participant"
          ),
        )
      }

      createCoinOwnBySvc(svcParticipant, 1.0)

      clue("start onboarding new SV and SVC party setup on new SV's dedicated participant") {
        // SV4 is configured to join the SVC. After the SV is onboarded, it will start the SVC party hosting on its own dedicated participant
        startAllSync(sv4Validator, sv4)
      }

      createCoinOwnBySvc(svcParticipant, 2.0)

      val globalDomainId = inside(sv4Participant.domains.list_connected()) { case Seq(domain) =>
        domain.domainId
      }

      eventually() {
        svcParticipant.participantX.topology.party_to_participant_mappings
          .list(
            operation = Some(TopologyChangeOpX.Replace),
            filterStore = globalDomainId.filterString,
            filterParty = svcPartyStr,
            filterParticipant = sv4Participant.participantX.id.toProtoPrimitive,
          ) should have size 1

        sv4Participant.participantX.topology.party_to_participant_mappings
          .list(
            operation = Some(TopologyChangeOpX.Replace),
            filterStore = globalDomainId.filterString,
            filterParty = svcPartyStr,
            filterParticipant = sv4Participant.participantX.id.toProtoPrimitive,
          ) should have size 1
        val coinFromSv4Participant = getCoins(sv4Participant, svcParty)
        val coinFromSvcParticipant = getCoins(svcParticipant, svcParty)

        coinFromSv4Participant should have size 2
        coinFromSv4Participant shouldBe coinFromSvcParticipant

        sv4Participant.ledger_api.acs.of_party(svcParty) should not be empty
      }

      clue("sv4 can exercise CoinRules_DevNet_Tap without disclosed contracts or extra observer.") {
        val sv4Party = sv4.getSvcInfo().svParty

        val coinRules = sv4Participant.ledger_api_extensions.acs
          .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
          .head

        val openRound = sv4Participant.ledger_api_extensions.acs
          .filterJava(cc.round.OpenMiningRound.COMPANION)(
            svcParty,
            _.data.opensAt.isBefore(Instant.now),
          )
          .maxBy(_.data.round.number)

        sv4Participant.ledger_api_extensions.commands.submitWithResult(
          sv4.config.ledgerApiUser,
          actAs = Seq(sv4Party),
          readAs = Seq(svcParty),
          update = coinRules.id.exerciseCoinRules_DevNet_Tap(
            sv4Party.toProtoPrimitive,
            BigDecimal(100.0).bigDecimal,
            openRound.id,
          ),
        )

        def checkCoinContract(participant: CNParticipantClientReference, party: PartyId) = {
          val coins = getCoins(participant, party, _.data.owner == sv4Party.toProtoPrimitive)
          inside(coins) { case Seq(coin) =>
            coin.data.svc shouldBe svcPartyStr
            coin.data.amount.initialAmount shouldBe BigDecimal(100.0).bigDecimal.setScale(10)
            coin.data.owner shouldBe sv4Party.toProtoPrimitive
          }
        }

        eventually() {
          checkCoinContract(svcParticipant, svcParty)
          checkCoinContract(sv4Participant, sv4Party)
        }
      }

      clue("sv4 can restart") {
        sv4.stop()
        sv4.startSync()
      }
  }

  "SVs can update their CoinPriceVote contracts" in { implicit env =>
    initSvc()
    val svParties = Seq(("sv1", sv1), ("sv2", sv2), ("sv3", sv3), ("sv4", sv4)).map {
      case (svName, sv) => svName -> sv.getSvcInfo().svParty
    }.toMap

    clue("initially only sv1 and sv2 have set the CoinPriceVote") {
      // sv1 because it's the SVC founder and sv2 because we configured it to do so
      eventually() {
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv3") -> Seq(None),
          svParties("sv4") -> Seq(None),
        )
      }
    }

    actAndCheck(
      "set CoinPriceVote of sv2, sv3 and sv4", {
        sv2.updateCoinPriceVote(BigDecimal(4.0))
        sv3.updateCoinPriceVote(BigDecimal(3.0))
        sv4.updateCoinPriceVote(BigDecimal(2.0))
      },
    )(
      "CoinPriceVote contract for sv2, sv3 anc sv4 are updated",
      _ => {
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )

    actAndCheck(
      "update CoinPriceVote of sv1", {
        sv1.updateCoinPriceVote(BigDecimal(5.0))
      },
    )(
      "CoinPriceVote contract for sv1 are updated",
      _ => {
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(5.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )

    actAndCheck(
      "restarting all SVs", {
        svs.foreach(_.stop())
        startAllSync(svs: _*)
      },
    )(
      "CoinPriceVote contracts didn't change",
      _ => {
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(5.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(4.0))),
          svParties("sv3") -> Seq(Some(BigDecimal(3.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(2.0))),
        )
      },
    )
  }

  "archive duplicated and non-member CoinPriceVote contracts" in { implicit env =>
    initSvc()
    val svParties = Seq(("sv1", sv1), ("sv2", sv2), ("sv3", sv3), ("sv4", sv4)).map {
      case (svName, sv) => svName -> sv.getSvcInfo().svParty
    }.toMap

    eventually() {
      getCoinPriceVoteMap() shouldBe Map(
        svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
        svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
        svParties("sv3") -> Seq(None),
        svParties("sv4") -> Seq(None),
      )
    }

    actAndCheck(
      "create duplicated vote for sv4", {
        createCoinPriceVote(svParties("sv4"), Some(BigDecimal(3.0)))
        createCoinPriceVote(svParties("sv4"), Some(BigDecimal(4.0)))
      },
    )(
      "observed duplicated coin price of sv4",
      _ =>
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv3") -> Seq(None),
          svParties("sv4") -> Seq(None, Some(BigDecimal(3.0)), Some(BigDecimal(4.0))),
        ),
    )

    actAndCheck(
      "execute an action to remove sv3 on svcRules contract to trigger `GarbageCollectCoinPriceVotesTrigger` to remove duplicated and non member votes", {
        svc.participantClient.ledger_api_extensions.commands.submitWithResult(
          svc.config.svUser,
          actAs = Seq(svcParty),
          readAs = Seq.empty,
          update = sv1
            .getSvcInfo()
            .svcRules
            .contractId
            .exerciseSvcRules_RemoveMember(
              svParties("sv3").toProtoPrimitive
            ),
        )
      },
    )(
      "vote of sv3 is removed and the all votes of sv4 are removed except the latest vote",
      _ =>
        getCoinPriceVoteMap() shouldBe Map(
          svParties("sv1") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv2") -> Seq(Some(BigDecimal(1.0))),
          svParties("sv4") -> Seq(Some(BigDecimal(4.0))),
        ),
    )
  }

  "At least 3 SV votes are required to remove a member in devnet" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    val (sv5Party, _) = actAndCheck(
      "Add 1 phantom SVs to SVC", {
        val sv5Name = "sv5"
        val sv5PartyId = allocateRandomSvParty(sv5Name)
        addSvMember(sv5PartyId, sv5Name)
        sv5PartyId.toProtoPrimitive
      },
    )(
      "There should be 5 SVC members in total now",
      _ => {
        sv1.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    val (_, voteRequestCid) = actAndCheck(
      "SV1 create a vote request to remove sv5", {
        val action: ActionRequiringConfirmation =
          new ARC_SvcRules(new SRARC_RemoveMember(new SvcRules_RemoveMember(sv5Party)))
        sv1.createVoteRequest(
          sv1.getSvcInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
        )
      },
    )(
      "The vote request has been created and SV1 accepts as he created it",
      _ => {
        sv1.listVoteRequests() should not be empty
        val head = sv1.listVoteRequests().head.contractId
        sv1.listVotes(Vector(head.contractId)) should have size 1
        head
      },
    )

    actAndCheck(
      "SV2 votes on removing sv5", {
        sv2.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not remove sv5",
      _ => {
        sv2.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    actAndCheck(
      "SV3 refuses to remove sv5", {
        sv3.castVote(voteRequestCid, false, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not remove sv5",
      _ => {
        sv3.getSvcInfo().svcRules.payload.members should have size 5
      },
    )

    actAndCheck(
      "SV4 votes on removing sv5", {
        sv4.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should remove sv5",
      _ => {
        sv4.getSvcInfo().svcRules.payload.members should have size 4
      },
    )

  }

  "At least 3 SVs can vote on changing the SvcRules Configuration" in { implicit env =>
    val newNumUnclaimedRewardsThreshold = 42

    clue("Initialize SVC with 4 SVs") {
      initSvc()
      eventually() {
        sv1.getSvcInfo().svcRules.payload.members should have size 4
      }
    }

    val (_, (voteRequestCid, initialNumUnclaimedRewardsThreshold)) = actAndCheck(
      "SV1 create a vote request for a new SvcRules Configuration", {
        val newConfig = new SvcRulesConfig(
          newNumUnclaimedRewardsThreshold,
          sv1.getSvcInfo().svcRules.payload.config.actionConfirmationTimeout,
          sv1.getSvcInfo().svcRules.payload.config.svOnboardingRequestTimeout,
          sv1.getSvcInfo().svcRules.payload.config.svOnboardingConfirmedTimeout,
          sv1.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
          sv1.getSvcInfo().svcRules.payload.config.leaderInactiveTimeout,
          sv1.getSvcInfo().svcRules.payload.config.domainNodeConfigLimits,
          sv1.getSvcInfo().svcRules.payload.config.maxTextLength,
          sv1.getSvcInfo().svcRules.payload.config.globalDomain,
        )

        val action: ActionRequiringConfirmation =
          new ARC_SvcRules(new SRARC_SetConfig(new SvcRules_SetConfig(newConfig)))

        sv1.createVoteRequest(
          sv1.getSvcInfo().svParty.toProtoPrimitive,
          action,
          "url",
          "description",
        )
      },
    )(
      "The vote request has been created and SV1 accepts as he created it",
      _ => {
        sv1.listVoteRequests() should not be empty
        val head = sv1.listVoteRequests().head.contractId
        sv1.listVotes(Vector(head.contractId)) should have size 1
        (head, sv1.getSvcInfo().svcRules.payload.config.numUnclaimedRewardsThreshold)
      },
    )

    actAndCheck(
      "SV2 votes on accepting the new configuration", {
        sv2.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority did not vote yet, thus the trigger should not change the svcRules",
      _ => {
        sv2
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe initialNumUnclaimedRewardsThreshold
      },
    )

    actAndCheck(
      "SV3 refuses the new configuration", {
        sv3.castVote(voteRequestCid, false, "url", "description")
      },
    )(
      "The majority has voted but without an acceptance majority, the trigger should not change the svcRules",
      _ => {
        sv3
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe initialNumUnclaimedRewardsThreshold
      },
    )

    actAndCheck(
      "SV4 votes on accepting the new configuration", {
        sv4.castVote(voteRequestCid, true, "url", "description")
      },
    )(
      "The majority accepts, the trigger should change the svcRules accordingly",
      _ => {
        sv4
          .getSvcInfo()
          .svcRules
          .payload
          .config
          .numUnclaimedRewardsThreshold shouldBe newNumUnclaimedRewardsThreshold
      },
    )

  }

  private def getCoinPriceVoteMap()(implicit env: CNNodeTestConsoleEnvironment) =
    sv1
      .listCoinPriceVotes()
      .groupBy(_.payload.sv)
      .flatMap { case (sv, contracts) =>
        Codec
          .decode(Codec.Party)(sv)
          .map(p =>
            p -> contracts.map(
              _.payload.coinPrice.toScala.map(BigDecimal(_))
            )
          )
          .toOption
      }

  private def expiringAmount(amount: Double) = new cc.fees.ExpiringAmount(
    BigDecimal(amount).bigDecimal,
    new cc.api.v1.round.Round(0L),
    new cc.fees.RatePerRound(BigDecimal(amount).bigDecimal),
  )

  private def coin(amount: Double, party: PartyId) = new cc.coin.Coin(
    party.toProtoPrimitive,
    party.toProtoPrimitive,
    expiringAmount(amount),
  )

  private def createCoinOwnBySvc(
      participant: CNParticipantClientReference,
      amount: Double,
  )(implicit env: CNNodeTestConsoleEnvironment) =
    participant.ledger_api_extensions.commands.submitWithResult(
      svc.config.svUser,
      actAs = Seq(svcParty),
      readAs = Seq.empty,
      update = coin(amount, svcParty).create,
    )

  private def getCoins(
      participant: CNParticipantClientReference,
      party: PartyId,
      predicate: cc.coin.Coin.Contract => Boolean = _ => true,
  ): Seq[cc.coin.Coin.Contract] = {
    participant.ledger_api_extensions.acs
      .filterJava(cc.coin.Coin.COMPANION)(party, predicate)
      .sortBy(_.data.amount.initialAmount)
  }

  private def createCoinPriceVote(
      svParty: PartyId,
      coinPrice: Option[BigDecimal],
  )(implicit env: CNNodeTestConsoleEnvironment) =
    svc.participantClient.ledger_api_extensions.commands.submitWithResult(
      svc.config.svUser,
      actAs = Seq(svcParty),
      readAs = Seq.empty,
      update = new cn.svc.coinprice.CoinPriceVote(
        svcParty.toProtoPrimitive,
        svParty.toProtoPrimitive,
        coinPrice.map(_.bigDecimal).toJava,
        Instant.now(),
      ).create,
    )

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
