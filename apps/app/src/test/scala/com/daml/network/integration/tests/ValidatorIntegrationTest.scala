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
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level
import com.daml.network.util.WalletTestUtil
import scala.concurrent.Future

class ValidatorIntegrationTest extends CoinIntegrationTest with WalletTestUtil {

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
    splitwellValidator.startSync()
    splitwellValidator.stop()
    splitwellValidator.startSync()
  }

  "initialize svc and validator apps" in { implicit env =>
    initSvc()
    // Check that there is exactly one CoinRule and OpenMiningRound
    val coinRules = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
      .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
    coinRules should have length 1

    val openRounds = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
      .filterJava(cc.round.OpenMiningRound.COMPANION)(svcParty)
    openRounds should have length 3

    // Start Alice’s validator
    aliceValidator.startSync()

    // check that alice's validator can see its license.
    val aliceValidatorParty = aliceValidator.getValidatorPartyId()
    aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
      .awaitJava(ValidatorLicense.COMPANION)(aliceValidatorParty)

    // onboard end user
    aliceValidator.onboardUser(aliceWallet.config.ledgerApiUser)
  }

  // TODO(M3-46) clean up once every validator uses this onboarding flow
  "onboard validator via onboarding config" in { implicit env =>
    initSvc()

    // Stop sv1 (the leader), so we are sure that `CoinRulesRequests` won't be processed.
    sv1.stop()

    clue("Start Bob’s validator, who is configured with a `ValidatorOnboardingConfig`") {
      bobValidator.startSync()
    }
    val bobValidatorParty = bobValidator.getValidatorPartyId()

    clue("Bob's validator can see its own ValidatorLicense") {
      inside(
        bobValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
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
      bobValidator.stop()
      bobValidator.startSync()
    }
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

    val headers = aliceValidator.headers
    val validResponse = Http()
      .singleRequest(registerPost.withHeaders(headers))
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

  "onboard, list and offboard users" in { implicit env =>
    initSvc()
    aliceValidator.startSync()
    aliceWalletBackend.startSync()

    actAndCheck("Onboard a user", onboardWalletUser(aliceWallet, aliceValidator))(
      "Wait for user to be listed",
      _ => {
        val usernames = aliceValidatorClient.listUsers()
        usernames should contain theSameElementsAs Seq(
          aliceWallet.config.ledgerApiUser,
          aliceValidator.config.validatorWalletUser.value,
        )
      },
    )

    val numTestUsers = 30
    val prefix = "test-user"
    val testUsers = Range(0, numTestUsers).map(i => s"${prefix}-${i}")

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      testUsers.foreach(aliceValidatorClient.onboardUser(_)),
      entries => {
        forAtLeast(numTestUsers, entries)(
          _.message should include(
            s"Completed processing with outcome: onboarded wallet end-user '${prefix}"
          )
        )
      },
    )

    aliceWallet.tap(100.0)

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      actAndCheck(
        "Offboard a user",
        aliceValidatorClient.offboardUser(aliceWallet.config.ledgerApiUser),
      )(
        "Wait for the validator to report the user offboarded",
        _ => {
          // Wait for the user to no longer be listed in the validator's users list, but this
          // does not yet guarantee their wallet services have been closed.
          val usernames = aliceValidatorClient.listUsers()
          usernames should contain theSameElementsAs (testUsers ++
            Seq(aliceValidator.config.validatorWalletUser.value))
        },
      ),
      entries => {
        forAtLeast(1, entries)(
          // Wait for the wallet to log that it has completed offboarding of the user
          _.message should include(
            s"offboarded user ${aliceWallet.config.ledgerApiUser} from wallet"
          )
        )
      },
    )

    clue("Offboarding alice again - should fail") {
      assertThrowsAndLogsCommandFailures(
        aliceValidatorClient.offboardUser(aliceWallet.config.ledgerApiUser),
        _.errorMessage should include("No install contract found for user"),
      )
    }

    actAndCheck(
      "Onboarding alice back",
      aliceValidatorClient.onboardUser(aliceWallet.config.ledgerApiUser),
    )(
      "Alice should have retained her coin",
      aliceParty => checkWallet(aliceParty, aliceWallet, Seq((100.0, 100.0))),
    )

    implicit val ec = env.executionContext
    clue("Offboarding many users in parallel") {
      // Note that there's two sources of parallelism here - we submit many requests in parallel
      // (hence the use of Futures here), and also the wallet automation will pick up
      // several offboarding requests in parallel.
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        testUsers.map(user => Future { aliceValidatorClient.offboardUser(user) }),
        entries => {
          forAtLeast(numTestUsers, entries)(
            _.message should (include(
              s"offboarded user ${prefix}"
            ) and endWith("from wallet"))
          )
        },
      )
    }

  }

  "stop an uninitialized validator" in { implicit env =>
    // No svc initialized, so the validator will not succeed in initialization,
    // but the test will terminate and close it before any initialization timeout
    aliceValidator.start()
  }

  // TODO(#3272): Adding a test here (e.g. by uncommenting the following) throws an ERROR "Channel ManagedChannelImpl{logId=2370, target=0.0.0.0:5012} was not shutdown properly"
  // "this fails" in { implicit env =>
  //   initSvc()
  // }
}
