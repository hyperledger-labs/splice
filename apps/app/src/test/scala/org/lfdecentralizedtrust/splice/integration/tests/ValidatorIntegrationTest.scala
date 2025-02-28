package org.lfdecentralizedtrust.splice.integration.tests

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.{BaseLedgerConnection, EnvironmentImpl}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import org.lfdecentralizedtrust.splice.validator.config.ValidatorAppBackendConfig
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.sequencing.SubmissionRequestAmplification
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.transaction.ParticipantPermission
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.{Get, Post}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.slf4j.event.Level

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Random, Try}

class ValidatorIntegrationTest extends IntegrationTest with WalletTestUtil {

  private val invalidValidator = "aliceValidatorInvalid"
  private val validatorPartyHint = s"imnotvalid_${(new scala.util.Random).nextInt(10000)}"

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart
      .addConfigTransformToFront((_, config) => {
        val aliceValidatorConfig = config
          .validatorApps(InstanceName.tryCreate("aliceValidator"))
        config.copy(
          validatorApps = config.validatorApps + (
            InstanceName.tryCreate(invalidValidator) ->
              aliceValidatorConfig
                .copy(
                  validatorPartyHint = Some(validatorPartyHint),
                  ledgerApiUser = s"${aliceValidatorConfig.ledgerApiUser}2",
                )
          )
        )
      })
      .addConfigTransformToFront((_, config) => {
        // Set a broken sequencer URL for sv4 to test that validators can still successfully connect.
        config
          .focus(_.svApps)
          .modify(_.updatedWith(InstanceName.tryCreate("sv4")) {
            _.map(
              _.focus(_.localSynchronizerNode).modify(
                _.map(_.focus(_.sequencer.externalPublicApiUrl).replace("http://example.com"))
              )
            )
          })
      })

  "start and restart cleanly" in { implicit env =>
    initDsoWithSv1Only()
    splitwellValidatorBackend.startSync()
    splitwellValidatorBackend.stop()
    splitwellValidatorBackend.startSync()
  }

  "initialize DSO and validator apps" in { implicit env =>
    initDsoWithSv1Only()
    // Check that there is exactly one AmuletRule and OpenMiningRound
    val amuletRules = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(splice.amuletrules.AmuletRules.COMPANION)(dsoParty)
    amuletRules should have length 1

    val openRounds = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(splice.round.OpenMiningRound.COMPANION)(dsoParty)
    openRounds should have length 3

    // Start Alice’s validator
    aliceValidatorBackend.startSync()

    // check that alice's validator can see its license.
    val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
      .awaitJava(ValidatorLicense.COMPANION)(aliceValidatorParty)

    // onboard end user
    aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)

    // the party is available via the scan proxy
    aliceValidatorBackend.scanProxy.getDsoParty() shouldBe dsoParty

    // the ans rules are available via the scan proxy
    aliceValidatorBackend.scanProxy
      .getAnsRules()
      .toAssignedContract
      .value
      .contract
      .payload
      .dso shouldBe dsoParty.toProtoPrimitive
  }

  "validator apps connect to all DSO sequencers" in { implicit env =>
    initDso()

    // Start Alice’s validator
    aliceValidatorBackend.startSync()

    // check that alice's validator connects to all DSO sequencers.
    // we need to wait for a minute due to non sv validator only connect to sequencers after initialization + sequencerAvailabilityDelay which is is 60s
    eventually(timeUntilSuccess = 1.minutes, maxPollInterval = 1.seconds) {
      val sequencerConnections = aliceValidatorBackend.participantClientWithAdminToken.synchronizers
        .config(
          aliceValidatorBackend.config.domains.global.alias
        )
        .value
        .sequencerConnections
      sequencerConnections.connections.size shouldBe 4
      sequencerConnections.sequencerTrustThreshold shouldBe PositiveInt.tryCreate(2)
      sequencerConnections.submissionRequestAmplification shouldBe SubmissionRequestAmplification(
        PositiveInt.tryCreate(2),
        ValidatorAppBackendConfig.DEFAULT_SEQUENCER_REQUEST_AMPLIFICATION_PATIENCE,
      )
    }
  }

  "onboard users with party hint sanitizer" in { implicit env =>
    initDsoWithSv1Only()
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
    initDsoWithSv1Only()
    aliceValidatorBackend.startSync()

    val aliceUser = aliceWalletClient.config.ledgerApiUser
    val aliceToken = Some(
      AuthUtil.testToken(
        audience = AuthUtil.testAudience,
        user = aliceUser,
        secret = AuthUtil.testSecret,
      )
    )
    val aliceValidatorClientWithToken = aliceValidatorClient.copy(token = aliceToken)
    // register() is the same as onboardUser(), but it reads the user name from the token
    val partyIdFromTokenUser = aliceValidatorClientWithToken.register()

    partyIdFromTokenUser.toString
      .split("::")
      .head should be(
      BaseLedgerConnection.sanitizeUserIdToPartyString(aliceUser)
    )
  }

  "fail registration with invalid tokens, succeed with a valid token" in { implicit env =>
    initDsoWithSv1Only()
    aliceValidatorBackend.startSync()

    implicit val sys = env.actorSystem
    registerHttpConnectionPoolsCleanup(env)

    val registerPost =
      Post(s"${aliceValidatorBackend.httpClientConfig.url}/api/validator/v0/register")
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
    initDsoWithSv1Only()
    aliceValidatorBackend.startSync()

    implicit val sys = env.actorSystem
    registerHttpConnectionPoolsCleanup(env)

    val listUsersGet =
      Get(s"${aliceValidatorBackend.httpClientConfig.url}/api/validator/v0/admin/users")

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

  "create and list ANS entries with multiple users for the same party" in { implicit env =>
    initDsoWithSv1Only()
    aliceValidatorBackend.startSync()
    val aliceParty = aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    aliceWalletClient.tap(10)

    val name = s"alice.unverified.$ansAcronym"
    val url = "https://alice-url.com"
    val description = "A test ANS entry for alice"

    val createResponse = aliceAnsExternalClient.createAnsEntry(name, url, description)
    createResponse.name shouldBe name
    createResponse.name shouldBe name
    createResponse.url shouldBe url
    createResponse.description shouldBe description

    aliceWalletClient.acceptSubscriptionRequest(createResponse.subscriptionRequestCid)

    val aliceEntries = eventually() {
      val entriesResponse = aliceAnsExternalClient.listAnsEntries()
      entriesResponse.entries should have size 1
      entriesResponse
    }

    clue("Onboard charlie backed by alice's party") {
      aliceValidatorBackend.onboardUser(charlieWalletClient.config.ledgerApiUser, Some(aliceParty))
    }
    clue("Check ANS entries") {
      charlieAnsExternalClient.listAnsEntries() shouldBe aliceEntries
    }
    clue("Compare wallet tx logs") {
      val aliceTxs = aliceWalletClient.listTransactions(None, 10)
      val charlieTxs = charlieWalletClient.listTransactions(
        None,
        aliceTxs.size,
      ) // protect from flakes due to background activity
      aliceTxs shouldBe charlieTxs
    }
    clue("Offboard alice and check that charlie can still tap") {
      aliceValidatorBackend.offboardUser(aliceWalletClient.config.ledgerApiUser)
      charlieWalletClient.tap(10)
    }
  }

  "onboard user multiple times" in { implicit env =>
    initDsoWithSv1Only()
    aliceValidatorBackend.startSync()

    val party1 = aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    val party2 = aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    party1 shouldBe party2
  }

  "register user multiple times" in { implicit env =>
    initDsoWithSv1Only()
    aliceValidatorBackend.startSync()

    val aliceUser = aliceWalletClient.config.ledgerApiUser
    val aliceToken = Some(
      AuthUtil.testToken(
        audience = AuthUtil.testAudience,
        user = aliceUser,
        secret = AuthUtil.testSecret,
      )
    )
    val aliceValidatorClientWithToken = aliceValidatorClient.copy(token = aliceToken)

    // register() is the same as onboardUser(), but it reads the user name from the token
    val party1 = aliceValidatorClientWithToken.register()
    val party2 = aliceValidatorClientWithToken.register()
    party1 shouldBe party2
  }

  "onboard, list and offboard users" in { implicit env =>
    initDsoWithSv1Only()
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

    val numTestUsers = 15
    val prefix = "test-user"
    val testUsers = Range(0, numTestUsers).map(i => s"${prefix}-${i}")

    loggerFactory.assertEventuallyLogsSeq(
      SuppressionRule.LevelAndAbove(Level.DEBUG)
    )(
      testUsers.foreach(aliceValidatorBackend.onboardUser(_)),
      entries => {
        forAtLeast(numTestUsers, entries)(
          _.message should include(
            s"Completed processing with outcome: started wallet automation for end-user party ${prefix}"
          )
        )
      },
      timeUntilSuccess = 40.seconds,
    )

    val tapAmount = 100.0
    aliceWalletClient.tap(tapAmount)
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
      "Alice should have retained her amulet",
      _ => {
        val balance = Try(loggerFactory.suppressErrors((aliceWalletClient.balance())))
          .getOrElse(fail(s"Could not get balance for alice"))
        assertInRange(
          balance.unlockedQty,
          (walletUsdToAmulet(tapAmount - 0.1), walletUsdToAmulet(tapAmount)),
        )
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
                s"offboarded wallet for user party ${prefix}"
              ))
            )
          },
        )
      Future.sequence(offboardFutures).futureValue
    }

  }

  "support existing party with invalid hit" in { implicit env =>
    initDsoWithSv1Only()
    val validator = v(invalidValidator)
    val participantClientWithAdminToken = validator.participantClientWithAdminToken
    participantClientWithAdminToken.topology.party_to_participant_mappings.propose(
      PartyId.tryCreate(validatorPartyHint, participantClientWithAdminToken.id.namespace),
      Seq(
        (
          participantClientWithAdminToken.id,
          ParticipantPermission.Submission,
        )
      ),
    )
    val configuredUser = validator.config.ledgerApiUser

    def getUser = {
      participantClientWithAdminToken.ledger_api.users.get(configuredUser)
    }
    getUser.primaryParty shouldBe None
    validator.startSync()
    getUser.primaryParty.value.uid.identifier shouldBe validatorPartyHint
  }

}
