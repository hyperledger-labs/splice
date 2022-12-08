package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.LiveDevNetTest
import com.daml.network.auth.{AuthUtil, LoginParameters, UserCredential}
import com.daml.network.config.{CoinConfig, CoinConfigTransforms}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTest
import com.daml.network.util.Auth0User
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import scala.collection.mutable

/** Integration test for the runbook. Uses the exact same configuration files and bootstrap scripts as the runbook.
  * This test also doubles as the pre-flight validator test.
  */
class PreflightIntegrationTest
    extends FrontendIntegrationTest("preflight")
    with HasConsoleScriptRunner {

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  val resourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  val auth0Users: mutable.Map[String, Auth0User] = mutable.Map.empty[String, Auth0User]

  override def beforeEach() = {
    super.beforeEach();

    val auth0 = auth0UtilFromSystemPoperties("https://canton-network-dev.us.auth0.com")

    val aliceUser = auth0.createUser();
    logger.debug(
      s"Created user Alice ${aliceUser.email} with password ${aliceUser.password} (id: ${aliceUser.id})"
    )

    val bobUser = auth0.createUser();
    logger.debug(
      s"Created user Bob ${bobUser.email} with password ${bobUser.password} (id: ${bobUser.id})"
    )

    auth0Users += ("aliceValidator1Wallet" -> aliceUser)
    auth0Users += ("bobValidator1Wallet" -> bobUser)
  }

  override def afterEach() = {
    super.afterEach();
    auth0Users.values.map(_.close)
  }

  private def loginWithBrowser(
      uiUrl: String,
      userId: String,
      userEmail: String,
      password: String,
  ): UserCredential = {
    var token = "";

    withFrontEnd("preflight") { implicit webDriver =>
      go to uiUrl
      click on "oidc-login-button"

      clue("auth0 login") {
        textField(id("username")).value = userEmail
        find(id("password")).foreach(_.underlying.sendKeys(password))
        click on name("action")
      }

      eventually() {
        find(id("logout-button"))
      }

      val getTokenScript = """
        var key = Object.keys(localStorage).find(k => k.includes("oidc.user"))
        var userData = JSON.parse(localStorage.getItem(key))

        return userData.id_token
      """

      executeScript(getTokenScript) match {
        case s: String => token = s;
        case e => throw new Error("Unexpected result fetching token from localStorage: " + e)
      }

      click on "logout-button"
    }

    UserCredential(userId, Some(token))
  }

  /** Acquire tokens for particular users via some particular auth method
    *
    * @param config
    * @return A map of "old" usernames to UserCredential containing "new" username (if necessary) and associated token
    */
  private def acquireUserTokens(config: CoinConfig): Map[String, UserCredential] = {
    val authMethodForWalletClientRef: Map[String, LoginParameters] =
      auth0Users
        .map { case (wInstanceName, user) => (wInstanceName, user) }
        .foldLeft(Map.empty[String, LoginParameters]) {
          case (acc, (wInstanceName, user)) => {
            acc + (wInstanceName -> LoginParameters.Auth0(
              s"https://wallet.validator1.${System.getProperty("NETWORK_APPS_ADDRESS")}/",
              user.id,
              user.email,
              user.password,
            ))
          }
        }

    // Keep track of a map between initial user names and a generated UserCredential
    config
      .focus(_.walletAppClients)
      .get
      .map { case (wInstanceName, wConfig) => (wInstanceName, wConfig) }
      .foldLeft(Map.empty[String, UserCredential]) {
        case (acc, (wInstanceName, wConfig)) => {
          val userCred: UserCredential =
            authMethodForWalletClientRef.get(wInstanceName.toString) match {
              case None => UserCredential(wConfig.damlUser, None)
              case Some(auth) =>
                auth match {
                  case LoginParameters.SelfSigned(secret) => {
                    new UserCredential(
                      wConfig.damlUser,
                      Some(
                        AuthUtil.testToken(
                          audience = AuthUtil.audience(wConfig.adminApi.address, "wallet"),
                          user = wConfig.damlUser,
                          secret = secret,
                        )
                      ),
                    )
                  }
                  case LoginParameters.Auth0(uiUrl, userId, userEmail, password) =>
                    loginWithBrowser(uiUrl, userId, userEmail, password)
                }
            }
          acc + (wConfig.damlUser -> userCred)
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
        resourcesPath / "preflight-extras.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CoinConfigTransforms.addDamlNameSuffix("preflight")(conf))
      .addConfigTransforms((_, conf) => CoinConfigTransforms.ensureNovelDamlNames()(conf))
      .addConfigTransforms((_, conf) => CoinConfigTransforms.bumpCantonPortsBy(1000)(conf))
      .addConfigTransforms((_, conf) =>
        CoinConfigTransforms.addUserTokens(acquireUserTokens(conf))(conf)
      )
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
    runScript(resourcesPath / "tap-transfer-validator1.canton")(env.environment)
  }

  "test a directory entry allocation against cluster SVC" taggedAs LiveDevNetTest in {
    implicit env =>
      runScript(resourcesPath / "allocate-directory-entry.canton")(env.environment)
  }
}
