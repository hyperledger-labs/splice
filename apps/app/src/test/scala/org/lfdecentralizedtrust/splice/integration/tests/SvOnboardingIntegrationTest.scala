package org.lfdecentralizedtrust.splice.integration.tests

import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.Get
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.sv.util.ValidatorOnboardingSecret
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration.*
import scala.util.Random

class SvOnboardingIntegrationTest extends SvIntegrationTestBase {

  "fail registration with invalid tokens, succeed with a valid token" in { implicit env =>
    initDso()
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

  "SVs can onboard new validators" in { implicit env =>
    initDso()
    val sv = sv4Backend // not a delegate
    val svParty = sv.getDsoInfo().svParty
    val sv1Party = sv1Backend.getDsoInfo().svParty
    sv.listOngoingValidatorOnboardings() should have length 0

    val name = "dummy" + env.environment.config.name.getOrElse("")

    val (secret, onboardingContract) = actAndCheck(
      "the sv operator prepares the onboarding", {
        sv.prepareValidatorOnboarding(1.hour, Some(name))
      },
    )(
      "a validator onboarding contract is created",
      { secret =>
        {
          inside(sv.listOngoingValidatorOnboardings()) { case Seq(vo) =>
            vo.encodedSecret shouldBe secret
            vo.contract
          }
        }
      },
    )
    // Not starting validator so we need to connect the participant manually.
    val config =
      sv1Backend.participantClient.synchronizers
        .config(sv1Backend.config.domains.global.alias)
        .value
    bobValidatorBackend.participantClient.synchronizers.connect_by_config(config)
    bobValidatorBackend.participantClient.upload_dar_unless_exists(amuletDarPath)
    val candidate = clue("create a dummy party") {
      val name = "dummy" + env.environment.config.name.getOrElse("")
      bobValidatorBackend.participantClientWithAdminToken.ledger_api.parties
        .allocate(
          name,
          synchronizerId = Some(decentralizedSynchronizerId),
        )
        .party

    }
    val contactPoint = s"${candidate.uid.identifier}@example.com"
    clue("try to onboard with a wrong secret, which should fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "wrongsecret", contactPoint)
        )
      )
    }
    clue("try to onboarding with a secret for the wrong SV party") {
      assertThrowsAndLogsCommandFailures(
        sv.onboardValidator(
          candidate,
          ValidatorOnboardingSecret(
            sv1Party,
            onboardingContract.payload.candidateSecret,
            None,
          ).toApiResponse,
          contactPoint,
        ),
        _.errorMessage should include(
          s"Secret is for SV $sv1Party but this SV is $svParty, validate your SV sponsor URL"
        ),
      )
    }

    clue("try to onboard with a secret that has an incorrect party hint") {
      val partyId = PartyId
        .fromProtoPrimitive("invalid-name-1::dummy", "partyId")
        .getOrElse(sys.error("Could not parse PartyId"))

      assertThrowsAndLogsCommandFailures(
        sv.onboardValidator(
          partyId,
          onboardingContract.payload.candidateSecret,
          contactPoint,
        ),
        _.errorMessage should include(
          s"The onboarding secret entered does not match the secret issued for validatorPartyHint: $name"
        ),
      )
    }
    actAndCheck(
      "request to onboard the candidate",
      sv.onboardValidator(candidate, secret, s"${candidate.uid.identifier}@example.com"),
    )(
      "the candidate's secret is marked as used",
      _ => {
        inside(
          sv.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(splice.validatoronboarding.UsedSecret.COMPANION)(svParty)
        ) {
          case Seq(usedSecret) => {
            usedSecret.data.secret shouldBe onboardingContract.payload.candidateSecret
            usedSecret.data.validator shouldBe candidate.toProtoPrimitive
          }
        }
      },
    )
    clue("try to onboard with a wrong secret, which should still fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "wrongsecret", contactPoint)
        )
      )
    }
    clue("try to reuse the same secret for a second onboarding, which should succeed") {
      eventuallySucceeds() {
        sv.onboardValidator(candidate, secret, contactPoint)
      }
    }
    clue(
      "try to reuse the same secret in the legacy format without SV party id, which should succeed"
    ) {
      eventuallySucceeds() {
        sv.onboardValidator(candidate, onboardingContract.payload.candidateSecret, contactPoint)
      }
    }
  }

  "Validator candidates can self-service at the validator onboarding tap" in { implicit env =>
    initDso()
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
            vo.encodedSecret shouldBe secret
          }
        }
      },
    )
  }

  "SVs expect onboardings when asked to" in { implicit env =>
    initDso()
    clue("SV1 has created as many ValidatorOnboarding contracts as it's configured to.") {
      sv1Backend.listOngoingValidatorOnboardings() should have length 3
    }
    clue("SV1 doesn't recreate ValidatorOnboarding contracts on restart...") {
      sv1Backend.stop()
      sv1Backend.startSync()
      sv1Backend.listOngoingValidatorOnboardings() should have length 3
    }
    clue("...even if an onboarding was completed in the meantime...") {
      bobValidatorBackend.startSync()
      eventually() {
        sv1Backend.listOngoingValidatorOnboardings() should have length 2
      }
      sv1Backend.stop()
      sv1Backend.startSync()
      sv1Backend.listOngoingValidatorOnboardings() should have length 2
    }
  }

}
