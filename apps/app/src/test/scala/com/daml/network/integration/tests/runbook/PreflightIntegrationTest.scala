package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.LiveDevNetTest
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTest
import com.daml.network.util.{
  Auth0User,
  CantonProcessTestUtil,
  SplitwellFrontendTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import com.digitalasset.canton.topology.PartyId
import monocle.macros.syntax.lens.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Using

/** Integration test for the runbook. Uses the exact same configuration files and bootstrap scripts as the runbook.
  * This test also doubles as the pre-flight validator test.
  */
class PreflightIntegrationTest
    extends FrontendIntegrationTest("alice-v1", "bob-v1")
    with HasConsoleScriptRunner
    with CantonProcessTestUtil
    with WalletTestUtil
    with WalletFrontendTestUtil
    with SplitwellFrontendTestUtil {

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  val resourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  val auth0Users: mutable.Map[String, Auth0User] = mutable.Map.empty[String, Auth0User]

  // We cache this because we only need it for one test case
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var validatorOnboardingSecret: Option[String] = None

  override def beforeEach() = {
    super.beforeEach();

    val auth0 = auth0UtilFromEnvVars("https://canton-network-dev.us.auth0.com")

    val aliceUser = retryAuth0Calls(auth0.createUser());
    logger.debug(
      s"Created user Alice ${aliceUser.email} with password ${aliceUser.password} (id: ${aliceUser.id})"
    )

    val bobUser = retryAuth0Calls(auth0.createUser());
    logger.debug(
      s"Created user Bob ${bobUser.email} with password ${bobUser.password} (id: ${bobUser.id})"
    )

    auth0Users += ("alice-v1" -> aliceUser)
    auth0Users += ("bob-v1" -> bobUser)
  }

  override def afterEach() = {
    super.afterEach();
    auth0Users.values.map(user => retryAuth0Calls(user.close))
  }

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        validatorPath / "validator-onboarding-nosecret.conf",
      )
      // clearing default config transforms because they have settings
      // we don't want such as adjusting daml names or triggering automation every second
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(1000)(conf))
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.useSelfSignedTokensForWalletValidatorApiAuth("test")(conf)
      )
      // Disable autostart, because our apps require the participant to be connected to a domain
      // when the app starts. The apps are started manually in `validator-participant.sc` below.
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // Obtain a fresh onboarding secret from a SV because this is what we want runbook users to do.
      .addConfigTransforms((_, conf) => insertValidatorOnboardingSecret(conf))

  // when running locally, these tests may fail if the CC DAR deployed to DevNet
  // differs from the latest one on your branch

  "run through runbook against cluster validator1" taggedAs LiveDevNetTest in { _ =>
    val walletUiUrl = s"https://wallet.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";

    val aliceUser = auth0Users.get("alice-v1").value

    val bobUser = auth0Users.get("bob-v1").value

    var alicePartyId = ""

    withFrontEnd("alice-v1") { implicit webDriver =>
      alicePartyId = loginAndOnboardToWalletUi(aliceUser, walletUiUrl)
      findAll(className("coins-table-row")) should have size 0
    }

    var bobPartyId = ""

    withFrontEnd("bob-v1") { implicit webDriver =>
      bobPartyId = loginAndOnboardToWalletUi(bobUser, walletUiUrl)

      findAll(className("coins-table-row")) should have size 0
    }

    withFrontEnd("alice-v1") { implicit webDriver =>
      tapAndListCoins(100)

      createTransferOffer(bobPartyId, "10", "p2ptransfer")

      click on "logout-button"
      waitForQuery(id("oidc-login-button"))
    }

    withFrontEnd("bob-v1") { implicit webDriver =>
      click on "transfer-offers-button"

      val acceptButton = eventually() {
        findAll(className("transfer-offers-row")).toSeq.headOption match {
          case Some(element) =>
            element.childWebElement(className("transfer-offers-table-accept"))
          case None => fail("failed to find transfer offer")
        }
      }

      acceptButton.click

      click on "coins-button"

      // TODO(#1985) -- cluster is slow to display updated list of Coins for Bob
      eventually(60.seconds) {
        val coinsTableRows = findAll(className("coins-table-row"))
        coinsTableRows should have size 1
      }

      val coinsTableRows = findAll(className("coins-table-row"))
      coinsTableRows.toSeq.head.underlying.getText() contains ("10.0000000000CC")

      click on "logout-button"
      waitForQuery(id("oidc-login-button"))
    }
  }

  // test is similar to 'settle debts with a single party' in SplitwellFrontendIntegrationTest
  "test splitwell group creation and payment" taggedAs LiveDevNetTest in { _ =>
    var aliceUserPartyId = ""
    var bobUserPartyId = ""

    val groupName = "troika"

    val walletUiUrl = s"https://wallet.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val splitwellUiUrl = s"https://splitwell.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val aliceUser = auth0Users.get("alice-v1").value
    val bobUser = auth0Users.get("bob-v1").value

    withFrontEnd("bob-v1") { implicit webDriver =>
      bobUserPartyId = loginAndOnboardToWalletUi(bobUser, walletUiUrl)

      tapAndListCoins(710)
    }

    val invite = withFrontEnd("alice-v1") { implicit webDriver =>
      aliceUserPartyId = loginAndOnboardToWalletUi(aliceUser, walletUiUrl)
      tapAndListCoins(60)
      loginToSplitwellUi(aliceUser, splitwellUiUrl)

      createGroupAndInviteLink(groupName)
    }

    withFrontEnd("bob-v1") { implicit webDriver =>
      loginToSplitwellUi(bobUser, splitwellUiUrl)
      requestGroupMembership(invite)
    }

    withFrontEnd("alice-v1") { implicit webDriver =>
      actAndCheck("add user", click on className("add-user-link"))(
        "user has been added and invite link disappears",
        _ => findAll(className("add-user-link")).toSeq shouldBe empty,
      )
      addTeamLunch(100)
    }

    withFrontEnd("bob-v1") { implicit webDriver =>
      enterSplitwellPayment(
        aliceUserPartyId,
        PartyId.tryFromProtoPrimitive(aliceUserPartyId),
        50,
        complete = false,
      )

      // Bob is redirected to wallet ..
      click on className("accept-button")

      // And then back to splitwell, where he is already logged in
      eventually(scaled(5 seconds)) {
        inside(findAll(className("balances-table-row")).toSeq) { case Seq(row) =>
          row.childElement(className("balances-table-receiver")).text should matchText(
            aliceUserPartyId
          )
          row.childElement(className("balances-table-amount")).text.toDouble shouldBe 0.0
        }
        val rows = findAll(className("balance-updates-list-item")).toSeq
        rows should have size 2
        // We don't guarantee an order on ACS requests atm so we assert independent of the specific order.
        forExactly(1, rows)(
          _.text should matchText(
            s"${aliceUserPartyId} paid 100.0000000000 CC for Team lunch"
          )
        )
        forExactly(1, rows)(
          _.text should matchText(
            s"${bobUserPartyId} sent 50.0000000000 CC to ${aliceUserPartyId}"
          )
        )
      }
    }

  }

  "test a directory entry allocation against cluster deployment" taggedAs LiveDevNetTest in { _ =>
    val walletUiUrl = s"https://wallet.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val directoryUiUrl =
      s"https://directory.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";

    val aliceUser = auth0Users.get("alice-v1").value

    withFrontEnd("alice-v1") { implicit webDriver =>
      loginAndOnboardToWalletUi(aliceUser, walletUiUrl)

      tapAndListCoins(100)

      allocateDirectoryEntry(aliceUser, directoryUiUrl, "alice.cns")
    }
  }

  private def allocateDirectoryEntry(
      auth0User: Auth0User,
      directoryUiUrl: String,
      entryName: String,
  )(implicit
      webDrive: WebDriverType
  ) = {
    go to directoryUiUrl
    click on "oidc-login-button"
    completeAuth0Prompts(
      auth0User.email,
      auth0User.password,
      () => find(id("entry-name-field")).isDefined,
    )

    waitForQuery(id("entry-name-field"))

    click on "entry-name-field"
    textField("entry-name-field").value = entryName

    click on "request-entry-with-sub-button"

    eventually() {
      findAll(className("sub-requests-table-row")) should have size 1
    }
  }

  def reserveDirectoryNameFor(auth0User: Auth0User, directoryUiUrl: String, entryName: String)(
      implicit webDrive: WebDriverType
  ): String = {
    allocateDirectoryEntry(auth0User, directoryUiUrl, entryName)

    // user is redirected to their wallet...
    eventually() {
      findAll(className("sub-request-accept-button")) should have size 1
    }
    click on className("sub-request-accept-button")

    // And then back to directory, where they are already logged in
    eventually(scaled(10 seconds)) {
      findAll(className("entries-table-row")) should have size 1
    }
    val row: Element = inside(findAll(className("entries-table-row")).toList) { case Seq(row) =>
      row
    }
    val name = row.childElement(className("entries-table-name"))
    name.text should be(entryName)
    entryName
  }

  "run through runbook against cluster deployment" taggedAs LiveDevNetTest in { implicit env =>
    // Start Canton as a separate process. We do that here rather than in the env setup
    // because it is only needed for this one test.
    val cantonArgs = Seq(
      "-c",
      (validatorPath / "validator-participant.conf").toString,
      "-C",
      "canton.participants.validatorParticipant.ledger-api.port=6001",
      "-C",
      "canton.participants.validatorParticipant.admin-api.port=6002",
      "--bootstrap",
      (validatorPath / "validator-participant.sc").toString,
    )
    Using.resource(startCanton(cantonArgs)) { process =>
      runScript(validatorPath / "validator.sc")(env.environment)
      runScript(validatorPath / "tap-transfer-demo.sc")(env.environment)
    }
  }

  private def insertValidatorOnboardingSecret(conf: CNNodeConfig): CNNodeConfig = {

    conf.validatorApps.size shouldBe 1

    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc => {
      val oc = vc.onboarding.value

      // obtain an onboarding secret
      val secret = validatorOnboardingSecret match {
        case Some(s) => s
        case None => {
          val s = prepareValidatorOnboarding(oc.remoteSv.adminApi.url)
          validatorOnboardingSecret = Some(s)
          s
        }
      }
      // insert it
      vc.focus(_.onboarding).replace(Some(oc.copy(secret = secret)))
    })(conf)
  }

  // We invoke the API via a basic HTTP request, just like we expect runbook users to do for now.
  private def prepareValidatorOnboarding(url: String): String = {
    val client = HttpClient
      .newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(20))
      .build()

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(url + "/admin/validator/onboarding/prepare"))
      .header("content-type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString("{\"expires_in\":3600}"))
      .build();

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val secret = (io.circe.parser.parse(response.body).value \\ "secret" head).asString.value
    secret
  }

  private def loginAndOnboardToWalletUi(
      user: Auth0User,
      walletUiUrl: String,
  )(implicit webDriver: WebDriverType): String = {
    loginAndOnboardToUiViaAuth0(user, walletUiUrl, onboardUserToWallet = true)
  }

  private def loginToSplitwellUi(
      user: Auth0User,
      url: String,
  )(implicit webDriver: WebDriverType) = {
    clue(s"Logging in and onboarding as user: ${user.email}") {
      go to url
      click on "oidc-login-button"
      completeAuth0Prompts(
        user.email,
        user.password,
        () => find(id("group-id-field")).isDefined,
      )

      waitForQuery(id("logged-in-user"))
    }
  }

  private def loginAndOnboardToUiViaAuth0(
      user: Auth0User,
      url: String,
      onboardUserToWallet: Boolean,
  )(implicit webDriver: WebDriverType): String = {
    clue(s"Logging in and onboarding as user: ${user.email}") {
      go to url
      click on "oidc-login-button"
      completeAuth0Prompts(
        user.email,
        user.password,
        () => find(id("onboard-button")).isDefined,
      )

      waitForQuery(id("onboard-button"))

      if (onboardUserToWallet)
        click on "onboard-button"

      eventually() {
        findAll(className("party-id")) should have size 1
      }
      copyPartyId()
    }
  }

  private def copyPartyId()(implicit webDriver: WebDriverType): String = {
    clue(s"Copying party ID") {
      find(className("party-id")).fold(throw new Error("Party ID display expected, but not found"))(
        elm => elm.text
      )
    }
  }

  private def createTransferOffer(receiverPartyId: String, amount: String, description: String)(
      implicit webDriver: WebDriverType
  ): Unit = {
    clue(s"Creating transfer offer for: $receiverPartyId") {
      click on "transfer-offers-button"

      click on "create-offer-button"

      setDirectoryField(textField("create-offer-receiver"), receiverPartyId, receiverPartyId)

      click on "create-offer-amount"
      numberField("create-offer-amount").underlying.sendKeys(amount)

      click on "create-offer-description"
      textField("create-offer-description").value = description

      click on "create-offer-expiration-value"
      numberField("create-offer-expiration-value").underlying.sendKeys("120")

      click on "submit-create-offer-button"

      eventually() {
        findAll(className("transfer-offers-row")) should have size 1
      }
    }
  }
}
