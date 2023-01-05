package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.LiveDevNetTest
import com.daml.network.config.CoinConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTest
import com.daml.network.util.Auth0User
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import scala.collection.mutable
import scala.concurrent.duration.*

/** Integration test for the runbook. Uses the exact same configuration files and bootstrap scripts as the runbook.
  * This test also doubles as the pre-flight validator test.
  */
class PreflightIntegrationTest
    extends FrontendIntegrationTest("alice-v1", "bob-v1")
    with HasConsoleScriptRunner {

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  val resourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  val auth0Users: mutable.Map[String, Auth0User] = mutable.Map.empty[String, Auth0User]

  override def beforeEach() = {
    super.beforeEach();

    val auth0 = auth0UtilFromEnvVars("https://canton-network-dev.us.auth0.com")

    val aliceUser = auth0.createUser();
    logger.debug(
      s"Created user Alice ${aliceUser.email} with password ${aliceUser.password} (id: ${aliceUser.id})"
    )

    val bobUser = auth0.createUser();
    logger.debug(
      s"Created user Bob ${bobUser.email} with password ${bobUser.password} (id: ${bobUser.id})"
    )

    auth0Users += ("alice-v1" -> aliceUser)
    auth0Users += ("bob-v1" -> bobUser)
  }

  override def afterEach() = {
    super.afterEach();
    auth0Users.values.map(_.close)
  }

  private def loginAndOnboardToWalletUi(
      user: Auth0User,
      walletUiUrl: String,
  )(implicit webDriver: WebDriverType): Unit = {
    clue(s"Logging in and onboarding as user: ${user.email}") {
      go to walletUiUrl
      click on "oidc-login-button"
      completeAuth0Prompts(
        user.email,
        user.password,
        () => find(id("onboard-button")).isDefined,
      )

      eventually() {
        find(id("onboard-button"))
      }

      click on "onboard-button"

      eventually() {
        findAll(className("party-id")) should have size 1
      }
    }
  }

  private def copyPartyId()(implicit webDriver: WebDriverType): String = {
    clue(s"Copying party ID") {
      find(className("party-id")).fold(throw new Error("Party ID display expected, but not found"))(
        elm => elm.text
      )
    }
  }

  private def createTransferOffer(receiverPartyId: String, quantity: String, description: String)(
      implicit webDriver: WebDriverType
  ): Unit = {
    clue(s"Creating transfer offer for: $receiverPartyId") {
      click on "transfer-offers-button"

      click on "create-offer-button"

      setDirectoryField(textField("create-offer-receiver"), receiverPartyId, receiverPartyId)

      click on "create-offer-quantity"
      numberField("create-offer-quantity").underlying.sendKeys(quantity)

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

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        validatorPath / "validator-participant.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CoinConfigTransforms.bumpCantonPortsBy(1000)(conf))
      .addConfigTransforms((_, conf) =>
        CoinConfigTransforms.useSelfSignedTokensForWalletValidatorApiAuth("test")(conf)
      )
      // Disable autostart, because our apps require the participant to be connected to a domain
      // when the app starts. The apps are started manually in `validator-participant.canton` below.
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))

  // when running locally, these tests may fail if the CC DAR deployed to DevNet
  // differs from the latest one on your branch

  "run through runbook against cluster validator1" taggedAs LiveDevNetTest in { _ =>
    val walletUiUrl = s"https://wallet.validator1.${System.getProperty("NETWORK_APPS_ADDRESS")}/";

    val aliceUser = auth0Users.get("alice-v1") match {
      case Some(user) => user
      case None => fail("could not get alice user from auth0")
    }

    val bobUser = auth0Users.get("bob-v1") match {
      case Some(user) => user
      case None => fail("could not get bob user from auth0")
    }

    var alicePartyId = ""

    withFrontEnd("alice-v1") { implicit webDriver =>
      loginAndOnboardToWalletUi(aliceUser, walletUiUrl)
      alicePartyId = copyPartyId()

      findAll(className("coins-table-row")) should have size 0
    }

    var bobPartyId = ""

    withFrontEnd("bob-v1") { implicit webDriver =>
      loginAndOnboardToWalletUi(bobUser, walletUiUrl)
      bobPartyId = copyPartyId()

      findAll(className("coins-table-row")) should have size 0
    }

    withFrontEnd("alice-v1") { implicit webDriver =>
      click on "tap-amount-field"
      numberField("tap-amount-field").underlying.sendKeys("100")
      click on "tap-button"
      eventually() {
        findAll(className("coins-table-row")) should have size 1
      }

      createTransferOffer(bobPartyId, "10", "p2ptransfer")

      click on "logout-button"
      eventually() {
        find(id("oidc-login-button"))
      }
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
      eventually() {
        find(id("oidc-login-button"))
      }
    }
  }

  "test a directory entry allocation against cluster deployment" taggedAs LiveDevNetTest in { _ =>
    val walletUiUrl = s"https://wallet.validator1.${System.getProperty("NETWORK_APPS_ADDRESS")}/";
    val directoryUiUrl =
      s"https://directory.validator1.${System.getProperty("NETWORK_APPS_ADDRESS")}/";

    val aliceUser = auth0Users.get("alice-v1") match {
      case Some(user) => user
      case None => fail("could not get alice user from auth0")
    }

    withFrontEnd("alice-v1") { implicit webDriver =>
      loginAndOnboardToWalletUi(aliceUser, walletUiUrl)

      click on "tap-amount-field"
      numberField("tap-amount-field").underlying.sendKeys("100")
      click on "tap-button"
      eventually() {
        findAll(className("coins-table-row")) should have size 1
      }

      go to directoryUiUrl

      click on "oidc-login-button"
      completeAuth0Prompts(
        aliceUser.email,
        aliceUser.password,
        () => find(id("entry-name-field")).isDefined,
      )

      eventually() {
        find(id("entry-name-field"))
      }

      click on "entry-name-field"
      textField("entry-name-field").value = "alice.cns"

      click on "request-entry-with-sub-button"

      eventually() {
        findAll(className("sub-requests-table-row")) should have size 1
      }
    }
  }

  "run through runbook against cluster deployment" taggedAs LiveDevNetTest in { implicit env =>
    runScript(validatorPath / "validator-participant.canton")(env.environment)
    runScript(validatorPath / "validator.canton")(env.environment)
    runScript(validatorPath / "tap-transfer-demo.canton")(env.environment)
  }
}
