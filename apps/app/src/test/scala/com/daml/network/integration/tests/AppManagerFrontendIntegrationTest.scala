package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.util.{FrontendLoginUtil, DirectoryFrontendTestUtil, WalletTestUtil}

import java.io.File

class AppManagerFrontendIntegrationTest
    extends FrontendIntegrationTest("splitwell", "alice")
    with WalletTestUtil
    with DirectoryFrontendTestUtil
    with FrontendLoginUtil {

  private val splitwellBundle = new File(
    "apps/splitwell/src/test/resources/splitwell-bundle.tar.gz"
  )

  override def environmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms(
        CNNodeConfigTransforms.onlySv1,
        (_, config) => CNNodeConfigTransforms.disableSplitwellUserDomainConnections(config),
      )

  "app manager" should {

    "register, install and launch splitwell" in { implicit env =>
      val installLink = withFrontEnd("splitwell") { implicit webDriver =>
        // login to wallet UI once to create saved localstorage auth session
        login(3102, splitwellValidatorBackend.config.ledgerApiUser)
        textField("register-app-name-input").underlying.sendKeys("splitwell")
        textField("register-app-ui-url-input").underlying.sendKeys("http://localhost:3002")
        find(className("register-app-release-bundle-input")).value.underlying
          .sendKeys(splitwellBundle.getAbsolutePath)
        click on id("register-app-add-domain-button")
        textField(className("register-app-domain-alias-input")).underlying.sendKeys("splitwell")
        textField(className("register-app-domain-url-input")).underlying
          .sendKeys("http://localhost:5108")
        val (_, link) =
          actAndCheck("Click on register app button", click on id("register-app-button"))(
            "App appears in listed apps",
            _ =>
              inside(findAll(className("registered-app")).toSeq) { case Seq(splitwell) =>
                splitwell.childElement(className("registered-app-name")).text shouldBe "splitwell"
                splitwell.childElement(className("registered-app-link")).attribute("href").value
              },
          )
        link
      }
      withFrontEnd("alice") { implicit webDriver =>
        login(3100, aliceValidatorBackend.config.ledgerApiUser)
        textField(id("install-app-input")).value = installLink
        actAndCheck("Click on install app button", click on id("install-app-button"))(
          "App appears in installed apps",
          _ =>
            inside(findAll(className("installed-app")).toSeq) { case Seq(splitwell) =>
              splitwell.childElement(className("installed-app-name")).text shouldBe "splitwell"
              click on splitwell.childElement(className("installed-app-link"))
            },
        )
        waitForQuery(id("oidc-login-button"))
        clickOn(id("oidc-login-button"))
        actAndCheck(
          "Login to app manager",
          loginOnCurrentPage(3100, aliceValidatorBackend.config.ledgerApiUser),
        )("authorize button appears", _ => find(id("authorize-button")) should not be empty)
        actAndCheck("Authorize app and get redirected", click on ("authorize-button"))(
          "splitwell UI shows up",
          _ =>
            // This also implies the install contract has been created
            // which means we can properly do writes through the user’s participant.
            find(id("create-group-button")) should not be empty,
        )
      }
    }
  }
}
