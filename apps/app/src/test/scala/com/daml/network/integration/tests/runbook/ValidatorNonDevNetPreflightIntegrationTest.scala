package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.util.WalletFrontendTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Base for preflight tests running against a deployed non-devnet validator
  */
abstract class ValidatorNonDevNetPreflightIntegrationTestBase
    extends FrontendIntegrationTestWithSharedEnvironment("validator-user", "sv1-user")
    with WalletFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  protected val validatorName: String

  protected lazy val validatorUserName =
    s"admin@${if (validatorName != "splitwell") validatorName else "sw"}.com"
  protected lazy val validatorUserPassword: String = sys.env(s"VALIDATOR_WEB_UI_PASSWORD")
  private lazy val validatorWalletUiUrl =
    s"https://wallet.$validatorName.${sys.env("NETWORK_APPS_ADDRESS")}/"

  protected val sv1UserName = s"admin@sv1.com"
  protected val sv1UserPassword: String = sys.env(s"SV_WEB_UI_PASSWORD")
  protected val sv1WalletUiUrl = s"https://wallet.sv-1.${sys.env("NETWORK_APPS_ADDRESS")}/"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "The validator service user's wallet is accessible" in { _ =>
    withFrontEnd("validator-user") { implicit webDriver =>
      clue(s"Logging in to wallet UI at: $validatorWalletUiUrl") {
        completeAuth0LoginWithAuthorization(
          validatorWalletUiUrl,
          validatorUserName,
          validatorUserPassword,
          // Notably, we expect that the service user is always already onboarded.
          () => find(id("wallet-balance-cc")) should not be empty,
        )
      }
      clue("Checking that balance is visible") {
        val balanceText = find(id("wallet-balance-cc")).value.text.trim
        logger.info(s"Current balance: $balanceText")
      }
    }
  }
}

class RunbookValidatorNonDevNetPreflightIntegrationTest
    extends ValidatorNonDevNetPreflightIntegrationTestBase {
  override protected val validatorName = "validator"
}

class Validator1NonDevNetPreflightIntegrationTest
    extends ValidatorNonDevNetPreflightIntegrationTestBase {
  override protected val validatorName = "validator1"
}

class SplitwellNonDevNetPreflightIntegrationTest
    extends ValidatorNonDevNetPreflightIntegrationTestBase {
  override protected val validatorName = "splitwell"
}
