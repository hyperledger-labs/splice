package com.daml.network.integration.tests.runbook

import com.daml.network.LiveDevNetTest
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTest
import com.daml.network.util.{
  Auth0User,
  DirectoryFrontendTestUtil,
  SplitwellFrontendTestUtil,
  WalletFrontendTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import scala.collection.mutable
import scala.concurrent.duration.*
import com.daml.network.util.FrontendLoginUtil

/** Preflight test running against validator1.
  */
class Validator1PreflightIntegrationTest
    extends FrontendIntegrationTest("alice-validator1", "bob-validator1")
    with FrontendLoginUtil
    with DirectoryFrontendTestUtil
    with WalletFrontendTestUtil
    with SplitwellFrontendTestUtil {

  private val auth0Users: mutable.Map[String, Auth0User] = mutable.Map.empty[String, Auth0User]

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

    auth0Users += ("alice-validator1" -> aliceUser)
    auth0Users += ("bob-validator1" -> bobUser)
  }

  override def afterEach() = {
    try super.afterEach()
    finally auth0Users.values.map(user => retryAuth0Calls(user.close))
  }

  override def beforeAll() = {
    super.beforeAll()
    // Offboard some users if we have too many, to make sure validator1 does not hit the limit of around 200.
    // Note that the actual offboarding will actually happen by wallet automation in the background,
    // and we are not waiting for it here, so it is expected to be happening in parallel to the actual tests
    limitValidator1Users()
  }

  private def limitValidator1Users() = {
    val env = createEnvironment()
    val validator1Client = env.validators.remote.find(_.name == "validator1").value
    val users = validator1Client.listUsers()
    val validatorWalletUser =
      "auth0|63e3d75ff4114d87a2c1e4f5" // TODO(tech-debt): consider de-hardcoding this
    val targetNumber = 80 // TODO(tech-debt): consider de-hardcoding this
    val offboardThreshold = 100 // TODO(tech-debt): consider de-hardcoding this
    if (users.length > offboardThreshold) {
      logger.info(
        s"Validator1 has ${users.length} users, offboarding some to get below ${targetNumber}"
      )
      users
        .filter(_ != validatorWalletUser)
        .take(users.length - targetNumber)
        .foreach { user =>
          {
            logger.debug(s"Offboarding user: ${user}")
            validator1Client.offboardUser(user)
          }
        }
    } else {
      logger.debug(s"Only ${users.length} users onboarded, not offboarding any")
    }
    destroyEnvironment(env)
  }

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName(),
      sys.env("NETWORK_APPS_ADDRESS"),
    )

  // when running locally, these tests may fail if the CC DAR deployed to DevNet
  // differs from the latest one on your branch

  "run through runbook against cluster validator1" taggedAs LiveDevNetTest in { _ =>
    val walletUiUrl = s"https://wallet.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";

    val aliceUser = auth0Users.get("alice-validator1").value

    val bobUser = auth0Users.get("bob-validator1").value

    var alicePartyId = ""

    withFrontEnd("alice-validator1") { implicit webDriver =>
      alicePartyId = loginAndOnboardToWalletUi(aliceUser, walletUiUrl)
      findAll(className("coins-table-row")) should have size 0
    }

    var bobPartyId = ""

    withFrontEnd("bob-validator1") { implicit webDriver =>
      bobPartyId = loginAndOnboardToWalletUi(bobUser, walletUiUrl)

      findAll(className("coins-table-row")) should have size 0
    }

    withFrontEnd("alice-validator1") { implicit webDriver =>
      tapAndListCoins(100)

      createTransferOffer(bobPartyId, "10", "p2ptransfer")

      click on "logout-button"
      waitForQuery(id("oidc-login-button"))
    }

    withFrontEnd("bob-validator1") { implicit webDriver =>
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
  "test splitwell group creation and payment against validator1" taggedAs LiveDevNetTest in { _ =>
    var aliceUserPartyId = ""
    var bobUserPartyId = ""

    val groupName = "troika"

    val walletUiUrl = s"https://wallet.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val splitwellUiUrl = s"https://splitwell.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val aliceUser = auth0Users.get("alice-validator1").value
    val bobUser = auth0Users.get("bob-validator1").value

    withFrontEnd("bob-validator1") { implicit webDriver =>
      bobUserPartyId = loginAndOnboardToWalletUi(bobUser, walletUiUrl)

      tapAndListCoins(710)
    }

    val invite = withFrontEnd("alice-validator1") { implicit webDriver =>
      aliceUserPartyId = loginAndOnboardToWalletUi(aliceUser, walletUiUrl)
      tapAndListCoins(60)
      loginToSplitwellUi(aliceUser, splitwellUiUrl)

      createGroupAndInviteLink(groupName)
    }

    withFrontEnd("bob-validator1") { implicit webDriver =>
      loginToSplitwellUi(bobUser, splitwellUiUrl)
      requestGroupMembership(invite)
    }

    withFrontEnd("alice-validator1") { implicit webDriver =>
      actAndCheck("add user", click on className("add-user-link"))(
        "user has been added and invite link disappears",
        _ => findAll(className("add-user-link")).toSeq shouldBe empty,
      )
      addTeamLunch(100)
    }

    withFrontEnd("bob-validator1") { implicit webDriver =>
      enterSplitwellPayment(
        aliceUserPartyId,
        PartyId.tryFromProtoPrimitive(aliceUserPartyId),
        50,
        complete = false,
      )

      // Bob is redirected to wallet ..
      click on className("accept-button")

      // And then back to splitwell, where he is already logged in
      eventually() {
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

  "test a directory entry allocation against validator1" taggedAs LiveDevNetTest in { _ =>
    val walletUiUrl = s"https://wallet.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";
    val directoryUiUrl =
      s"https://directory.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/";

    val aliceUser = auth0Users.get("alice-validator1").value

    withFrontEnd("alice-validator1") { implicit webDriver =>
      loginAndOnboardToWalletUi(aliceUser, walletUiUrl)

      tapAndListCoins(100)

      allocateDirectoryEntry(
        () => auth0Login(aliceUser, directoryUiUrl, () => find(id("entry-name-field")).isDefined),
        "alice.cns",
      )
    }
  }

  private def auth0Login(user: Auth0User, url: String, completedWhen: () => Boolean)(implicit
      webDriver: WebDriverType
  ) = {
    clue(s"Auth0 user login as: ${user.id} (${user.email})") {
      go to url
      click on "oidc-login-button"
      completeAuth0Prompts(
        user.email,
        user.password,
        completedWhen,
      )
    }
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
    clue(s"Logging in to splitwell UI at: ${url}") {
      auth0Login(user, url, () => find(id("group-id-field")).isDefined)
      waitForQuery(id("logged-in-user"))
    }
  }

  private def loginAndOnboardToUiViaAuth0(
      user: Auth0User,
      url: String,
      onboardUserToWallet: Boolean,
  )(implicit webDriver: WebDriverType): String = {
    clue(s"Logging in to wallet UI at: ${url}") {
      auth0Login(user, url, () => find(id("onboard-button")).isDefined)
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
