package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Base for preflight tests running against a deployed non-devnet validator
  */
abstract class ValidatorNonDevNetPreflightIntegrationTestBase
    extends FrontendIntegrationTestWithSharedEnvironment("validator-user") {

  protected val validatorName: String

  protected lazy val validatorUserName = s"admin@${validatorName}.com"
  protected lazy val validatorUserPassword = sys.env(s"VALIDATOR_WEB_UI_PASSWORD")

  private lazy val walletUiUrl =
    s"https://wallet.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"

  // TODO(#8848): Remove this option altogether (should always be true)
  protected val testBalance: Boolean = true

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "The validator service user's wallet is accessible and has non-zero balance" in { _ =>
    withFrontEnd("validator-user") { implicit webDriver =>
      clue(s"Logging in to wallet UI at: ${walletUiUrl}") {
        completeAuth0LoginWithAuthorization(
          walletUiUrl,
          validatorUserName,
          validatorUserPassword,
          // Notably, we expect that the service user is always already onboarded.
          () => find(id("wallet-balance-cc")) should not be empty,
        )
      }
      if (testBalance) {
        clue("Checking that balance is visible and > 0") {
          val balanceText = find(id("wallet-balance-cc")).value.text.trim
          val balance = BigDecimal(balanceText.split(" ").head)
          balance should be > BigDecimal(0)
        }
      }
    }
  }
}

class RunbookValidatorNonDevNetPreflightIntegrationTest
    extends ValidatorNonDevNetPreflightIntegrationTestBase {
  override protected val validatorName = "validator"
  override protected val testBalance = false
}

class Validator1NonDevNetPreflightIntegrationTest
    extends ValidatorNonDevNetPreflightIntegrationTestBase {
  override protected val validatorName = "validator1"
}
