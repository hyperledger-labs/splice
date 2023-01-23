package com.daml.network.integration.tests

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

class ValidatorIntegrationTest extends CoinIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // We manually start apps so we disable the default setup
      // that blocks on all apps being initialized.
      .withNoSetup()

  def initSvc()(implicit env: CoinTestConsoleEnvironment) = {
    env.appsHostedBySvc.local.foreach(_.start())
    env.appsHostedBySvc.local.foreach(_.waitForInitialization())
  }

  "start and restart cleanly" in { implicit env =>
    initSvc()
    aliceValidator.startSync()
    aliceValidator.stop()
    aliceValidator.startSync()
  }

  "initialize svc and validator apps" in { implicit env =>
    initSvc()
    // Check that there is exactly one CoinRule and OpenMiningRound
    val coinRules = svc.remoteParticipantWithAdminToken.ledger_api.acs
      .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
    coinRules should have length 1

    val openRounds = svc.remoteParticipantWithAdminToken.ledger_api.acs
      .filterJava(cc.round.OpenMiningRound.COMPANION)(svcParty)
    openRounds should have length 3

    // Start Alice’s validator
    aliceValidator.startSync()

    // Check that no coin rules request is outstanding
    eventually()(
      svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(cc.coin.CoinRulesRequest.COMPANION)(svcParty)
        shouldBe empty
    )

    // check that alice's validator can see the coinrules
    val aliceValidatorParty = aliceValidator.getValidatorPartyId()
    aliceValidator.remoteParticipantWithAdminToken.ledger_api.acs
      .awaitJava(cc.coin.CoinRules.COMPANION)(aliceValidatorParty)

    // onboard end user
    aliceValidator.onboardUser(aliceWallet.config.ledgerApiUser)
  }

  "onboard users with party hint sanitizer" in { implicit env =>
    initSvc()
    aliceValidator.startSync()

    // Make uniqueness of the user ID more probable when running the test multiple times in a row
    val randomId = (new scala.util.Random).nextInt(10000)

    val partyIdFromBadUserId = aliceValidator.onboardUser(s"test@example!#+~-user|123|${randomId}")
    partyIdFromBadUserId.toString
      .split("::")
      .head should fullyMatch regex (s"test_example____-user_123_${randomId}-.*")

    val partyIdFromGoodUserId = aliceValidator.onboardUser(s"other-_us:er-${randomId}")
    partyIdFromGoodUserId.toString
      .split("::")
      .head should fullyMatch regex (s"other-_us:er-${randomId}")
  }

  "register user" in { implicit env =>
    initSvc()
    aliceValidator.startSync()

    val partyIdFromTokenUser = aliceValidator.register()

    partyIdFromTokenUser.toString
      .split("::")
      .head should be(aliceValidator.config.ledgerApiUser)
  }

  "fail registration with invalid tokens, succeed with a valid token" in { implicit env =>
    initSvc()
    aliceValidator.startSync()

    implicit val sys = env.actorSystem
    implicit val ec = env.executionContext
    CoordinatedShutdown(sys).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "cleanup") {
      () =>
        Http().shutdownAllConnectionPools().map(_ => Done)
    }
    val registerPost = Post(s"${aliceValidator.httpClientConfig.url}/register")
    def tokenHeader(token: String) = Seq(Authorization(OAuth2BearerToken(token)))

    val invalidSignatureToken = JWT
      .create()
      .withAudience(aliceValidator.config.auth.audience)
      .withSubject(aliceValidator.config.ledgerApiUser)
      .sign(Algorithm.HMAC256("wrong-secret"))

    val responseForInvalidSignature = Http()
      .singleRequest(registerPost.withHeaders(tokenHeader(invalidSignatureToken)))
      .futureValue
    responseForInvalidSignature.status should be(StatusCodes.Unauthorized)

    val invalidAudienceToken = JWT
      .create()
      .withAudience("wrong-audience")
      .withSubject(aliceValidator.config.ledgerApiUser)
      .sign(AuthUtil.testSignatureAlgorithm)

    val responseForInvalidAudience = Http()
      .singleRequest(registerPost.withHeaders(tokenHeader(invalidAudienceToken)))
      .futureValue
    responseForInvalidAudience.status should be(StatusCodes.Unauthorized)

    val validToken = aliceValidator.token
    val validResponse = Http()
      .singleRequest(registerPost.withHeaders(tokenHeader(validToken)))
      .futureValue
    validResponse.status should be(StatusCodes.OK)
  }

  "onboard user multiple times" in { implicit env =>
    initSvc()
    aliceValidator.startSync()

    val party1 = aliceValidator.onboardUser(aliceWallet.config.ledgerApiUser)
    val party2 = aliceValidator.onboardUser(aliceWallet.config.ledgerApiUser)
    party1 shouldBe party2
  }

  "register user multiple times" in { implicit env =>
    initSvc()
    aliceValidator.startSync()

    val party1 = aliceValidator.register()
    val party2 = aliceValidator.register()
    party1 shouldBe party2
  }

  "list one connected domain" in { implicit env =>
    initSvc()
    aliceValidator.startSync()
    eventually() {
      aliceValidator.listConnectedDomains().keySet shouldBe Set("global", "splitwise")
    }
  }
}
