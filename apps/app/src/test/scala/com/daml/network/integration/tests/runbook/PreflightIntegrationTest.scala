package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.LiveDevNetTest
import com.daml.network.config.CoinConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTest
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import scala.util.Using

/** Integration test for the runbook. Uses the exact same configuration files and bootstrap scripts as the runbook.
  * This test also doubles as the pre-flight validator test.
  */
class PreflightIntegrationTest
    extends FrontendIntegrationTest("alice-v1", "bob-v1")
    with HasConsoleScriptRunner {

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  val resourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  private def loginAndOnboardToWalletUi(
      damlUser: String,
      walletUiUrl: String,
  )(implicit webDriver: WebDriverType): Unit = {
    clue(s"Logging in and onboarding as user: $damlUser") {
      go to walletUiUrl
      click on "user-id-field"
      textField("user-id-field").value = damlUser
      click on "login-button"

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

      // The fully qualified party ID is a mega long string, and it seems like React state is
      // too slow to fully update before the Selenium script moves on to the other fields.
      //
      // This resulted in only partially-complete party strings being sent in the request, and failing weirdly!
      //
      // Workaround: send keys one character at a time -- https://stackoverflow.com/a/71697436
      receiverPartyId.split("").foreach(textField("create-offer-receiver").underlying.sendKeys(_))

      click on "create-offer-quantity"
      numberField("create-offer-quantity").underlying.sendKeys(quantity)

      click on "create-offer-description"
      textField("create-offer-description").value = description

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
      .addConfigTransforms((_, conf) => CoinConfigTransforms.addDamlNameSuffix("preflight")(conf))
      .addConfigTransforms((_, conf) => CoinConfigTransforms.ensureNovelDamlNames()(conf))
      .addConfigTransforms((_, conf) => CoinConfigTransforms.bumpCantonPortsBy(1000)(conf))
      // Disable autostart, because our apps require the participant to be connected to a domain
      // when the app starts. The apps are started manually in `validator-participant.canton` below.
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))

  // when running locally, these tests may fail if the CC DAR deployed to DevNet
  // differs from the latest one on your branch

  "run through runbook against cluster SVC" taggedAs LiveDevNetTest in { implicit env =>
    runScript(validatorPath / "validator-participant.canton")(env.environment)
    runScript(validatorPath / "validator.canton")(env.environment)
    runScript(validatorPath / "tap-transfer-demo.canton")(env.environment)
  }

  "run through runbook against cluster validator1" taggedAs LiveDevNetTest in { implicit env =>
    val walletUiUrl = s"https://wallet.validator1.${System.getProperty("NETWORK_APPS_ADDRESS")}/";

    val aliceDamlUser = aliceWallet.config.damlUser
    val bobDamlUser = bobWallet.config.damlUser

    var alicePartyId = ""

    withFrontEnd("alice-v1") { implicit webDriver =>
      loginAndOnboardToWalletUi(aliceDamlUser, walletUiUrl)
      alicePartyId = copyPartyId()

      findAll(className("coins-table-row")) should have size 0
    }

    var bobPartyId = ""

    withFrontEnd("bob-v1") { implicit webDriver =>
      loginAndOnboardToWalletUi(bobDamlUser, walletUiUrl)
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

      eventually() {
        val coinsTableRows = findAll(className("coins-table-row"))
        coinsTableRows should have size 1
      }

      val coinsTableRows = findAll(className("coins-table-row"))
      coinsTableRows.toSeq.head.underlying.getText() contains ("10.0000000000CC")
    }
  }

  "test a directory entry allocation against cluster SVC" taggedAs LiveDevNetTest in {
    implicit env =>
      val walletUiUrl = s"https://wallet.validator1.${System.getProperty("NETWORK_APPS_ADDRESS")}/";
      val directoryUiUrl =
        s"https://directory.validator1.${System.getProperty("NETWORK_APPS_ADDRESS")}/";

      val aliceDamlUser = aliceWallet.config.damlUser

      withFrontEnd("alice-v1") { implicit webDriver =>
        loginAndOnboardToWalletUi(aliceDamlUser, walletUiUrl)

        click on "tap-amount-field"
        numberField("tap-amount-field").underlying.sendKeys("100")
        click on "tap-button"
        eventually() {
          findAll(className("coins-table-row")) should have size 1
        }

        go to directoryUiUrl

        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser

        click on "login-button"

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

  "work with auth0" taggedAs LiveDevNetTest in { _ =>
    val auth0 = auth0UtilFromSystemPoperties("https://canton-network-dev.us.auth0.com")
    Using.resource(auth0.createUser()) { user =>
      logger.debug(s"Created user ${user.email} with password ${user.password} (id: ${user.id})")
    }
  }
}
