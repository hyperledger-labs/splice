package com.daml.network.integration.tests

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{Get, Post}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.environment.{CNLedgerConnection, CNNodeEnvironmentImpl}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.slf4j.event.Level

import scala.concurrent.Future
import scala.util.{Random, Try}

class ValidatorIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withTrafficTopupsDisabled // TODO(#7222)
      .withManualStart

  "start and restart cleanly" in { implicit env =>
    initSvcWithSv1Only()
    splitwellValidatorBackend.startSync()
    splitwellValidatorBackend.stop()
    splitwellValidatorBackend.startSync()
  }

  "initialize svc and validator apps" in { implicit env =>
    initSvcWithSv1Only()
    // Check that there is exactly one CoinRule and OpenMiningRound
    val coinRules = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
    coinRules should have length 1

    val openRounds = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(cc.round.OpenMiningRound.COMPANION)(svcParty)
    openRounds should have length 3

    // Start Alice’s validator
    aliceValidatorBackend.startSync()

    // check that alice's validator can see its license.
    val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
      .awaitJava(ValidatorLicense.COMPANION)(aliceValidatorParty)

    // onboard end user
    aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
  }

  // TODO(M3-46) clean up once every validator uses this onboarding flow
  "onboard validator via onboarding config" in { implicit env =>
    initSvcWithSv1Only()

    clue("Start Bob’s validator, who is configured with a `ValidatorOnboardingConfig`") {
      bobValidatorBackend.startSync()
    }
    val bobValidatorParty = bobValidatorBackend.getValidatorPartyId()

    clue("Bob's validator can see its own ValidatorLicense") {
      inside(
        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.validatorlicense.ValidatorLicense.COMPANION)(
            bobValidatorParty
          )
      ) {
        case Seq(license) => {
          license.data.validator shouldBe bobValidatorParty.toProtoPrimitive
        }
      }
    }
    clue("Bob's validator can restart cleanly.") {
      bobValidatorBackend.stop()
      bobValidatorBackend.startSync()
    }
  }

  "onboard users with party hint sanitizer" in { implicit env =>
    initSvcWithSv1Only()
    aliceValidatorBackend.startSync()

    // Make uniqueness of the user ID more probable when running the test multiple times in a row
    val randomId = (new scala.util.Random).nextInt(10000)

    val partyIdFromBadUserId =
      aliceValidatorBackend.onboardUser(s"test@example!#+~-user|123|${randomId}")
    partyIdFromBadUserId.toString
      .split("::")
      .head should fullyMatch regex (s"test_0040example_0021_0023_002b_007e-user_007c123_007c${randomId}")

    val partyIdFromGoodUserId = aliceValidatorBackend.onboardUser(s"other-_us:er-${randomId}")
    partyIdFromGoodUserId.toString
      .split("::")
      .head should fullyMatch regex (s"other-__us:er-${randomId}")
  }

  "register user" in { implicit env =>
    initSvcWithSv1Only()
    aliceValidatorBackend.startSync()

    val partyIdFromTokenUser = aliceValidatorBackend.register()

    partyIdFromTokenUser.toString
      .split("::")
      .head should be(
      CNLedgerConnection.sanitizeUserIdToPartyString(aliceValidatorBackend.config.ledgerApiUser)
    )
  }

  "fail registration with invalid tokens, succeed with a valid token" in { implicit env =>
    initSvcWithSv1Only()
    aliceValidatorBackend.startSync()

    implicit val sys = env.actorSystem
    implicit val ec = env.executionContext
    CoordinatedShutdown(sys).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "cleanup") {
      () =>
        Http().shutdownAllConnectionPools().map(_ => Done)
    }
    val registerPost = Post(s"${aliceValidatorBackend.httpClientConfig.url}/register")
    def tokenHeader(token: String) = Seq(Authorization(OAuth2BearerToken(token)))

    val invalidSignatureToken = JWT
      .create()
      .withAudience(aliceValidatorBackend.config.auth.audience)
      .withSubject(aliceValidatorBackend.config.ledgerApiUser)
      .sign(Algorithm.HMAC256("wrong-secret"))

    val responseForInvalidSignature = Http()
      .singleRequest(registerPost.withHeaders(tokenHeader(invalidSignatureToken)))
      .futureValue
    responseForInvalidSignature.status should be(StatusCodes.Unauthorized)

    val invalidAudienceToken = JWT
      .create()
      .withAudience("wrong-audience")
      .withSubject(aliceValidatorBackend.config.ledgerApiUser)
      .sign(AuthUtil.testSignatureAlgorithm)

    val responseForInvalidAudience = Http()
      .singleRequest(registerPost.withHeaders(tokenHeader(invalidAudienceToken)))
      .futureValue
    responseForInvalidAudience.status should be(StatusCodes.Unauthorized)

    val headers = aliceValidatorBackend.headers
    val validResponse = Http()
      .singleRequest(registerPost.withHeaders(headers))
      .futureValue
    validResponse.status should be(StatusCodes.OK)
  }

  "fail admin endpoint when not authenticated as validator operator" in { implicit env =>
    initSvcWithSv1Only()
    aliceValidatorBackend.startSync()

    implicit val sys = env.actorSystem
    implicit val ec = env.executionContext
    CoordinatedShutdown(sys).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "cleanup") {
      () =>
        Http().shutdownAllConnectionPools().map(_ => Done)
    }

    val listUsersGet = Get(s"${aliceValidatorBackend.httpClientConfig.url}/admin/users")

    def tokenHeader(token: String) = Seq(Authorization(OAuth2BearerToken(token)))

    def makeRequest(userId: String) = {
      val invalidUserToken = JWT
        .create()
        .withAudience(aliceValidatorBackend.config.auth.audience)
        .withSubject(userId)
        .sign(AuthUtil.testSignatureAlgorithm)
      Http()
        .singleRequest(listUsersGet.withHeaders(tokenHeader(invalidUserToken)))
        .futureValue
    }

    clue("Invalid user id gets rejected") {
      loggerFactory.assertLogs(
        {
          val responseForInvalidUser = makeRequest("wrong_user")
          responseForInvalidUser.status should be(StatusCodes.Forbidden)
          responseForInvalidUser.entity.getContentType().toString should be("application/json")
        },
        _.warningMessage should include(
          "Authorization Failed"
        ),
      )
    }

    clue("User without actAs permissions for validator party gets rejected") {
      loggerFactory.assertLogs(
        {
          val validatorParty =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users
              .get(aliceValidatorBackend.config.ledgerApiUser)
              .primaryParty
              .value
          val testUser =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users.create(
              s"testUser-${Random.nextInt()}",
              actAs = Set.empty[PartyId],
              primaryParty = Some(validatorParty),
            )
          val response = makeRequest(testUser.id)
          response.status should be(StatusCodes.Forbidden)
        },
        _.warningMessage should include(
          "Authorization Failed"
        ),
      )
    }

    clue("User without validator party as primaryParty gets rejected") {
      loggerFactory.assertLogs(
        {
          val validatorParty =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users
              .get(aliceValidatorBackend.config.ledgerApiUser)
              .primaryParty
              .value
          val testUser =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users.create(
              s"testUser-${Random.nextInt()}",
              actAs = Set(validatorParty),
              primaryParty = None,
            )
          val response = makeRequest(testUser.id)
          response.status should be(StatusCodes.Forbidden)
        },
        _.warningMessage should include(
          "Authorization Failed"
        ),
      )
    }

    clue("User with actas rights and primaryParty gets accepted") {
      val validatorParty = aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users
        .get(aliceValidatorBackend.config.ledgerApiUser)
        .primaryParty
        .value
      val testUser = aliceValidatorBackend.participantClientWithAdminToken.ledger_api.users.create(
        s"testUser-${Random.nextInt()}",
        actAs = Set(validatorParty),
        primaryParty = Some(validatorParty),
      )
      val response = makeRequest(testUser.id)
      response.status should be(StatusCodes.OK)
    }
  }

  "onboard user multiple times" in { implicit env =>
    initSvcWithSv1Only()
    aliceValidatorBackend.startSync()

    val party1 = aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    val party2 = aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    party1 shouldBe party2
  }

  "register user multiple times" in { implicit env =>
    initSvcWithSv1Only()
    aliceValidatorBackend.startSync()

    val party1 = aliceValidatorBackend.register()
    val party2 = aliceValidatorBackend.register()
    party1 shouldBe party2
  }

  "onboard, list and offboard users" in { implicit env =>
    initSvcWithSv1Only()
    aliceValidatorBackend.startSync()

    actAndCheck("Onboard a user", onboardWalletUser(aliceWalletClient, aliceValidatorBackend))(
      "Wait for user to be listed",
      _ => {
        val usernames = aliceValidatorBackend.listUsers()
        usernames should contain theSameElementsAs Seq(
          aliceWalletClient.config.ledgerApiUser,
          aliceValidatorBackend.config.validatorWalletUser.value,
        )
      },
    )

    val numTestUsers = 30
    val prefix = "test-user"
    val testUsers = Range(0, numTestUsers).map(i => s"${prefix}-${i}")

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      testUsers.foreach(aliceValidatorBackend.onboardUser(_)),
      entries => {
        forAtLeast(numTestUsers, entries)(
          _.message should include(
            s"Completed processing with outcome: onboarded wallet end-user '${prefix}"
          )
        )
      },
    )

    aliceWalletClient.tap(100.0)
    assertUserFullyOnboarded(aliceWalletClient, aliceValidatorBackend)

    actAndCheck(
      "Offboard a user",
      aliceValidatorBackend.offboardUser(aliceWalletClient.config.ledgerApiUser),
    )(
      "Wait for the validator and wallet to report the user offboarded",
      _ => {
        val usernames = aliceValidatorBackend.listUsers()
        usernames should contain theSameElementsAs (testUsers ++
          Seq(aliceValidatorBackend.config.validatorWalletUser.value))
        assertUserFullyOffboarded(aliceWalletClient, aliceValidatorBackend)
      },
    )

    clue("Offboarding alice again - offboarding should be idempotent") {
      aliceValidatorBackend.offboardUser(aliceWalletClient.config.ledgerApiUser)
      assertUserFullyOffboarded(aliceWalletClient, aliceValidatorBackend)
    }

    actAndCheck(
      "Onboarding alice back",
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser),
    )(
      "Alice should have retained her coin",
      _ => {
        val balance = Try(loggerFactory.suppressErrors((aliceWalletClient.balance())))
          .getOrElse(fail(s"Could not get balance for alice"))
        balance.unlockedQty should be(100.0)
      },
    )

    implicit val ec = env.executionContext
    clue("Offboarding many users in parallel") {
      // Note that there's two sources of parallelism here - we submit many requests in parallel
      // (hence the use of Futures here), and also the wallet automation will pick up
      // several offboarding requests in parallel.
      val offboardFutures =
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          testUsers.map(user => Future { aliceValidatorBackend.offboardUser(user) }),
          entries => {
            forAtLeast(numTestUsers, entries)(
              _.message should (include(
                s"offboarded user ${prefix}"
              ) and endWith("from wallet"))
            )
          },
        )
      Future.sequence(offboardFutures).futureValue
    }

  }
}
