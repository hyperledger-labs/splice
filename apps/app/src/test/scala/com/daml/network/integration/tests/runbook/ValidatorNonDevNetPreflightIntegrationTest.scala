package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Base for preflight tests running against a deployed non-devnet validator
  */
abstract class ValidatorNonDevNetPreflightIntegrationTestBase
    extends FrontendIntegrationTestWithSharedEnvironment("anonymous") {

  protected val validatorName: String
  private lazy val walletUiUrl =
    s"https://wallet.${validatorName}.${sys.env("NETWORK_APPS_ADDRESS")}/"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  // TODO(#9059): Expand to more interesting test (with hardcoded users)
  "The wallet UI is accessible and shows a login page" in { _ =>
    withFrontEnd("anonymous") { implicit webDriver =>
      go to walletUiUrl
      eventually() {
        find(id("oidc-login-button")) should not be empty
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
