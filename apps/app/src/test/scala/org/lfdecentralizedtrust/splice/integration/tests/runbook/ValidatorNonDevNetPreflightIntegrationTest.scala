package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletFrontendTestUtil
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

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
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
          () => find(id("wallet-balance-amulet")) should not be empty,
        )
      }
      clue("Checking that balance is visible") {
        val balanceText = find(id("wallet-balance-amulet")).value.text.trim
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
