package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import org.lfdecentralizedtrust.splice.util.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.console.ValidatorAppClientReference
import org.lfdecentralizedtrust.splice.util.Auth0Util.WithAuth0Support

import java.net.URI
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.RichOptional
import scala.util.control.NonFatal

/** Base for preflight tests running against a deployed validator
  */
abstract class ValidatorPreflightIntegrationTestBase
    extends FrontendIntegrationTestWithSharedEnvironment(
      "alice-validator",
      "bob-validator",
      "charlie-validator",
    )
    with FrontendLoginUtil
    with PreflightIntegrationTestUtil
    with AnsFrontendTestUtil
    with WalletFrontendTestUtil
    with SplitwellFrontendTestUtil
    with WithAuth0Support {

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  protected val auth0Users: mutable.Map[String, Auth0User] = mutable.Map.empty[String, Auth0User]

  protected val isDevNet: Boolean = true

  protected lazy val isAuth0: Boolean = true
  protected val auth0: Auth0Util

  protected val validatorName: String
  protected val validatorAuth0Secret: String
  protected val validatorAuth0Audience: String
  protected val validatorWalletUser: String
  protected lazy val includeSplitwellTests = true

  protected lazy val walletUiUrl =
    s"https://wallet.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private lazy val ansUiUrl =
    s"https://cns.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private lazy val splitwellUiUrl =
    s"https://splitwell.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"

  override def beforeEach() = {
    super.beforeEach();

    def addUser(name: String)(implicit traceContext: TraceContext) = {
      val user = auth0.createUser()
      auth0Users += (name -> user)
    }

    TraceContext.withNewTraceContext(implicit traceContext => {
      try {
        addUser("alice")
        addUser("bob")
        addUser("charlie")
      } catch {
        case NonFatal(e) =>
          // Logging the error, as an exception in this method will abort the test suite with no log output.
          logger.error("addUser {alice,bob,charlie} beforeEach failed", e)
          throw e
      }
    })
  }

  override def afterEach() = {
    try super.afterEach()
    finally
      auth0Users.values.foreach(user => user.close())
  }

  override def beforeAll() = {
    super.beforeAll()
    // Offboard some users if we have too many, to make sure validator does not hit the limit of around 200.
    // Note that the actual offboarding will actually happen by wallet automation in the background,
    // and we are not waiting for it here, so it is expected to be happening in parallel to the actual tests
    limitValidatorUsers()
  }

  protected def validatorClient(suppressErrors: Boolean = true): ValidatorAppClientReference = {
    val env = provideEnvironment("NotUsed")
    // retry on e.g. network errors and rate limits
    val token = eventuallySucceeds(suppressErrors = suppressErrors) {
      Auth0Util.getAuth0ClientCredential(
        validatorAuth0Secret,
        validatorAuth0Audience,
        auth0.domain,
      )(noTracingLogger)
    }

    vc(validatorName)(env).copy(token = Some(token))
  }

  protected def limitValidatorUsers() = {
    // We skip offboarding users on non-auth0 validators. We don't use those on any long-running clusters,
    // where number of users becomes an issue.
    if (isAuth0) {
      val users = eventuallySucceeds()(validatorClient(suppressErrors = false).listUsers())
      val targetNumber = 40 // TODO(tech-debt): consider de-hardcoding this
      val offboardThreshold = 50 // TODO(tech-debt): consider de-hardcoding this
      if (users.length > offboardThreshold) {
        logger.info(
          s"Validator has ${users.length} users, offboarding some to get below ${targetNumber}"
        )
        users
          .filter(_ != validatorWalletUser)
          .take(users.length - targetNumber)
          .foreach { user =>
            {
              logger.debug(s"Offboarding user: ${user}")
              eventuallySucceeds()(validatorClient(suppressErrors = false).offboardUser(user))
            }
          }
      } else {
        logger.debug(s"Only ${users.length} users onboarded, not offboarding any")
      }
    }
  }

  protected def checkValidatorIsConnectedToSvRunbook() = {}

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName()
    )

  private def logout()(implicit webDriver: WebDriverType) = {
    clue("Logging out") {
      eventuallyClickOn(id("logout-button"))
      eventually() {
        (find(id("oidc-login-button")).isDefined || find(
          id("user-id-field")
        ).isDefined) shouldBe true
      }
    }
  }

  // when running locally, these tests may fail if the CC DAR deployed to DevNet
  // differs from the latest one on your branch

  checkValidatorIsConnectedToSvRunbook()

  "run through runbook against cluster validator" in { implicit env =>
    val alicePartyId = withFrontEnd("alice-validator") { implicit webDriver =>
      val alicePartyId = loginAndOnboardToWalletUi("alice")
      findAll(className("amulets-table-row")) should have size 0
      alicePartyId
    }

    val bobPartyId = withFrontEnd("bob-validator") { implicit webDriver =>
      val bobPartyId = loginAndOnboardToWalletUi("bob")
      findAll(className("amulets-table-row")) should have size 0
      bobPartyId
    }

    withFrontEnd("alice-validator") { implicit webDriver =>
      if (isDevNet) {
        tapAmulets(100)

        clue(s"Creating transfer offer for: $bobPartyId") {
          createTransferOffer(
            PartyId.tryFromProtoPrimitive(bobPartyId),
            BigDecimal("10"),
            90,
            "p2ptransfer",
          )
        }
      }

      logout()
    }

    withFrontEnd("bob-validator") { implicit webDriver =>
      if (isDevNet) {
        val acceptButton = eventually() {
          findAll(className("transfer-offer")).toSeq.headOption match {
            case Some(element) =>
              element.childWebElement(className("transfer-offer-accept"))
            case None => fail("failed to find transfer offer")
          }
        }

        // In preflights we have a higher chance of domain reconnects so we have to account for those
        actAndCheck(2.minutes)(
          "Accept transfer offer", {
            click on acceptButton
            eventuallyClickOn(id("navlink-transactions"))
          },
        )(
          "Transfer appears in transactions log",
          _ => {
            // There will be two tx log entries, one for the creation of the offer and one for the acceptance
            // when using the token standard flow and one otherwise.
            // We support both and just check that the entry for the completed transfer is there.
            val txs = findAll(className("tx-row")).toSeq
            forExactly(1, txs) { tx =>
              val transaction = readTransactionFromRow(tx)
              transaction.action should matchText("Received")
              val partyR = s"$alicePartyId.*".r
              val description =
                transaction.partyDescription.getOrElse(fail("There should be a party."))
              description should fullyMatch regex partyR
              transaction.ccAmount should beWithin(BigDecimal(10) - smallAmount, BigDecimal(10))
              // we can't test a specific amulet price as the amulet price on a live network can change
              val rateR =
                raw"""^\s*(\d+(?:\.\d+)?)\s*${amuletNameAcronym}/USD\s*$$""".r
              inside(transaction.rate.value) { case rateR(rate) =>
                BigDecimal(rate) should be > BigDecimal(0)
                transaction.usdAmount should beWithin(
                  transaction.ccAmount / BigDecimal(rate) - smallAmount,
                  transaction.ccAmount / BigDecimal(rate) + smallAmount,
                )
              }
            }
          },
        )
      }

      logout()
    }

    if (isAuth0) {
      val charlieUser = auth0Users.get("charlie").value
      clue("Onboard charlie manually to share a party with Bob") {
        validatorClient().onboardUser(
          charlieUser.id,
          Some(PartyId.tryFromProtoPrimitive(bobPartyId)),
        )
      }

      withFrontEnd("charlie-validator") { implicit webDriver =>
        clue("Charlie should be able to login without onboarding and share a party with Bob") {
          auth0Login(
            charlieUser,
            walletUiUrl,
            () => {
              logger.debug(
                s"Checking party ID for charlie: ${find(className("party-id"))} (bob's is ${bobPartyId}"
              )
              find(className("party-id")) should not be None
              val charliePartyId = seleniumText(find(className("party-id")))
              logger.debug(
                s"Checking party ID for charlie (it's not none): ${charliePartyId} (bob's is ${bobPartyId}"
              )
              charliePartyId should be(bobPartyId)
            },
          )
        }
        logout()
      }
    }
  }

  // test is similar to 'settle debts with a single party' in SplitwellFrontendIntegrationTest
  "test splitwell group creation and payment against validator" in { implicit env =>
    if (includeSplitwellTests) {
      val groupName = "troika"

      val aliceUser = auth0Users.get("alice").value
      val bobUser = auth0Users.get("bob").value

      val bobUserPartyId = withFrontEnd("bob-validator") { implicit webDriver =>
        val bobUserPartyId = loginAndOnboardToWalletUi("bob")
        if (isDevNet) {
          tapAmulets(710)
        }
        bobUserPartyId
      }

      val (aliceUserPartyId, invite) = withFrontEnd("alice-validator") { implicit webDriver =>
        val aliceUserPartyId = loginAndOnboardToWalletUi("alice")
        loginToSplitwellUi(aliceUser)

        (aliceUserPartyId, createGroupAndInviteLink(groupName))
      }

      withFrontEnd("bob-validator") { implicit webDriver =>
        loginToSplitwellUi(bobUser)
        requestGroupMembership(invite)
      }

      withFrontEnd("alice-validator") { implicit webDriver =>
        eventually() {
          findAll(className("add-user-link")).toSeq should not be (empty)
        }
        actAndCheck("add user", eventuallyClickOn(className("add-user-link")))(
          "user has been added and invite link disappears",
          _ => findAll(className("add-user-link")).toSeq shouldBe empty,
        )
        addTeamLunch(100)
      }

      if (isDevNet) {
        withFrontEnd("bob-validator") { implicit webDriver =>
          enterSplitwellPayment(
            aliceUserPartyId,
            PartyId.tryFromProtoPrimitive(aliceUserPartyId),
            50,
          )

          // Bob is redirected to wallet ..
          clue("accept payment in wallet") {
            eventuallyClickOn(className("payment-accept"))
          }

          // And then back to splitwell, where he is already logged in.
          // Accepting the payment (which triggers the redirect) and seeing
          // the balance update in the splitwell UI both take time,
          // so we use an eventually for each check.
          eventually(60.seconds) {
            findAll(className("balances-table-row")).toSeq.headOption
              .valueOrFail("Failed to find balances table. Did the payment succeed?")
          }
          eventually(60.seconds) {
            inside(findAll(className("balances-table-row")).toSeq) { case Seq(row) =>
              seleniumText(
                row.childElement(className("balances-table-receiver"))
              ) should matchText(aliceUserPartyId)

              row.childElement(className("balances-table-amount")).text.toDouble shouldBe 0.0
            }
            val rows = findAll(className("balance-updates-list-item")).toSeq
            rows should have size 2
            // We don't guarantee an order on ACS requests atm so we assert independent of the specific order.
            forExactly(1, rows)(row =>
              matchRow(
                Seq("sender", "description"),
                Seq(
                  aliceUserPartyId,
                  s"paid 100.0 ${amuletNameAcronym} for Team lunch",
                ),
              )(row)
            )
            forExactly(1, rows)(row =>
              matchRow(
                Seq("sender", "description", "receiver"),
                Seq(
                  bobUserPartyId,
                  s"sent 50.0 ${amuletNameAcronym} to",
                  aliceUserPartyId,
                ),
              )(row)
            )
          }
        }
      }
    }
  }

  "test the Name Service UI of a validator" in { implicit env =>
    withFrontEnd("alice-validator") { implicit webDriver =>
      loginAndOnboardToWalletUi("alice")

      if (isDevNet) {
        // On DevNet-like clusters, we test the full ANS entry creation flow

        // Generate new random ANS names to avoid conflicts between multiple preflight check runs
        val entryId = (new scala.util.Random).nextInt().toHexString
        val ansName = s"alice_${entryId}.unverified.$ansAcronym"

        tapAmulets(100)
        reserveAnsNameFor(
          () => {
            if (isAuth0) {
              auth0Login(
                auth0Users.get("alice").value,
                ansUiUrl,
                () => {
                  waitForQuery(id("entry-name-field"))
                  find(id("entry-name-field")) should not be empty
                },
              )
            } else {
              go to ansUiUrl
              waitForQuery(id("user-id-field"))
              loginOnceConfirmedToBeAtUrl("alice")
            }
          },
          ansName,
          "1.0000000000",
          "USD",
          "90 days",
          ansAcronym,
        )
      } else {
        // On non-DevNet clusters, we only test logging in to the directory UI
        isAuth0 shouldBe true // We don't currently test non-auth0 on non-devnet clusters
        auth0Login(
          auth0Users.get("alice").value,
          ansUiUrl,
          () => {
            waitForQuery(id("entry-name-field"))
            find(id("entry-name-field")) should not be empty
          },
        )
      }
    }
  }

  "can dump participant identities of validator" in { _ =>
    if (isAuth0) {
      eventuallySucceeds() {
        validatorClient(suppressErrors = false).dumpParticipantIdentities()
      }
    }
  }

  "connect to all sequencers stated in latest DsoRules contract" in { implicit env =>
    if (isAuth0) {
      val sv1ScanClient = scancl("sv1Scan")
      eventually() {
        val connections = inside(sv1ScanClient.listDsoSequencers()) {
          case Seq(DomainSequencers(_, connections)) => connections
        }
        connections should not be empty
        val latestMigrationId = connections.map(_.migrationId).max
        val availableConnections = connections.filter(connection =>
          connection.migrationId == latestMigrationId &&
            connection.url != "" &&
            // added 60s grace period for the polling trigger interval 30s + other latency
            env.environment.clock.now.toInstant.isAfter(connection.availableAfter.plusSeconds(60))
        )
        val (expectedSequencerConnections, _) =
          Endpoint
            .fromUris(NonEmpty.from(availableConnections.map(conn => new URI(conn.url))).value)
            .value

        val domainConnectionConfig = validatorClient().decentralizedSynchronizerConnectionConfig()
        val connectedEndpointSet =
          domainConnectionConfig.sequencerConnections.connections.flatMap(_.endpoints).toSet

        connectedEndpointSet should contain allElementsOf expectedSequencerConnections.map(
          _.toString
        )

        domainConnectionConfig.sequencerConnections.sequencerTrustThreshold shouldBe Thresholds
          .sequencerConnectionsSizeThreshold(
            domainConnectionConfig.sequencerConnections.connections.size
          )
          .value
        domainConnectionConfig.sequencerConnections.submissionRequestAmplification.factor shouldBe Thresholds
          .sequencerSubmissionRequestAmplification(
            domainConnectionConfig.sequencerConnections.connections.size
          )
          .value
      }
    }
  }

  private def auth0Login(
      user: Auth0User,
      url: String,
      assertCompleted: () => org.scalatest.Assertion,
  )(implicit
      webDriver: WebDriverType
  ) = {
    clue(s"Auth0 user login as: ${user.id} (${user.email})") {
      completeAuth0LoginWithAuthorization(
        url,
        user.email,
        user.password,
        assertCompleted,
      )
    }
  }

  private def loginAndOnboardToWalletUi(
      username: String
  )(implicit webDriver: WebDriverType): String = {
    if (isAuth0) {
      loginAndOnboardToUiViaAuth0(auth0Users.get(username).value, walletUiUrl)
    } else {
      loginAndOnboardNoAuth(username, walletUiUrl)
    }
  }

  private def loginToSplitwellUi(
      user: Auth0User
  )(implicit webDriver: WebDriverType) = {
    clue(s"Logging in to splitwell UI at: ${splitwellUiUrl}") {
      auth0Login(
        user,
        splitwellUiUrl,
        () => find(id("group-id-field")) should not be empty,
      )
      waitForQuery(id("logged-in-user"))
    }
  }

  private def loginAndOnboardNoAuth(
      username: String,
      url: String,
  )(implicit webDriver: WebDriverType): String = {
    go to url
    waitForQuery(id("user-id-field"))
    loginOnceConfirmedToBeAtUrl(username)
    onboardUserAfterLogin()
    copyPartyId()
  }

  private def loginAndOnboardToUiViaAuth0(
      user: Auth0User,
      url: String,
  )(implicit webDriver: WebDriverType): String = {
    clue(s"Logging in to wallet UI at: ${url}") {
      auth0Login(
        user,
        url,
        () => find(id("onboard-button")) should not be empty,
      )
    }
    onboardUserAfterLogin()
    copyPartyId()
  }

  private def onboardUserAfterLogin()(implicit webDriver: WebDriverType) = {
    // After login, the UI fetches the user onboarding status from the validator.
    // If the user is already onboarded, the party ID is displayed
    // If the user is not onboarded, the onboard button is displayed
    eventually() {
      (find(id("onboard-button")).isDefined || find(className("party-id")).isDefined) shouldBe true
    }

    if (find(id("onboard-button")).isDefined) {
      // TODO(DACH-NY/canton-network-internal#485): This is a workaround to bypass slowness of wallet user onboarding
      actAndCheck(timeUntilSuccess = 2.minute)(
        "Onboard wallet user", {
          eventuallyClickOn(id("onboard-button"))
        },
      )(
        "Party ID is displayed after onboarding finishes",
        _ => {
          find(className("party-id")) should not be None
        },
      )
    } else {
      logger.debug("User is already onboarded")
      find(className("party-id")) should not be None
    }
  }

  private def copyPartyId()(implicit webDriver: WebDriverType): String = {
    clue(s"Copying party ID") {
      find(className("party-id")).fold(throw new Error("Party ID display expected, but not found"))(
        elm => seleniumText(elm)
      )
    }
  }
}

class RunbookValidatorPreflightIntegrationTest extends ValidatorPreflightIntegrationTestBase {

  override protected val isDevNet = true
  override protected val auth0 = auth0UtilFromEnvVars("validator")

  override protected val validatorName = "validator"
  override protected val validatorAuth0Secret = sys.env("SPLICE_OAUTH_TEST_CLIENT_ID_VALIDATOR")
  override protected val validatorAuth0Audience = "https://validator.example.com/api"
  override protected lazy val includeSplitwellTests = false

  override protected val validatorWalletUser = sys.env("SPLICE_OAUTH_TEST_VALIDATOR_WALLET_USER")

  // TODO(#979): remove this check once canton handles sequencer connections more gracefully
  override def checkValidatorIsConnectedToSvRunbook() = "Validator is connected to SV runbook" in {
    implicit env =>
      val sv = sv_client("sv")
      eventually() {
        val dsoInfo = sv.getDsoInfo()
        val nodeState = dsoInfo.svNodeStates.get(dsoInfo.svParty).value.payload
        val domainConfig = nodeState.state.synchronizerNodes.asScala.values.headOption.value
        val (svSequencerEndpoint, _) = Endpoint
          .fromUris(NonEmpty.from(Seq(new URI(domainConfig.sequencer.toScala.value.url))).value)
          .value
        val domainConnectionConfig = validatorClient().decentralizedSynchronizerConnectionConfig()
        val connectedEndpointSet =
          domainConnectionConfig.sequencerConnections.connections.flatMap(_.endpoints).toSet
        connectedEndpointSet should contain(svSequencerEndpoint.forgetNE.loneElement.toString)
      }
  }

}

class Validator1PreflightIntegrationTest extends ValidatorPreflightIntegrationTestBase {

  override protected val isDevNet = true
  override protected val auth0 = auth0UtilFromEnvVars("dev")
  override protected val validatorName = "validator1"
  override protected val validatorAuth0Secret =
    sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_VALIDATOR1")
  override protected val validatorAuth0Audience = sys.env("OIDC_AUTHORITY_VALIDATOR_AUDIENCE")
  override protected val validatorWalletUser = sys.env("SPLICE_OAUTH_DEV_VALIDATOR_WALLET_USER")
  override protected lazy val isAuth0 = checkIfAuth0()
  override protected lazy val includeSplitwellTests = isAuth0

  def checkIfAuth0(): Boolean = {
    withFrontEnd("alice-validator") { implicit webDriver =>
      go to walletUiUrl
      eventually() {
        if (find(id("logout-button")).isDefined) {
          eventuallyClickOn(id("logout-button"))
        }
        if (!find(id("user-id-field")).isDefined && !find(id("oidc-login-button")).isDefined) {
          fail()
        }
        find(id("oidc-login-button")).isDefined
      }
    }
  }
}
