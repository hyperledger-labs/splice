package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.util.WalletFrontendTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

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
  protected val sv1WalletUiUrl = s"https://wallet.sv-1.svc.${sys.env("NETWORK_APPS_ADDRESS")}/"

  private val minimalBalance = 1_000
  private val targetBalance = 10_000

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "The validator service user's wallet is accessible and has sufficient balance" in { _ =>
    val (recipient: PartyId, balance: BigDecimal) = withFrontEnd("validator-user") {
      implicit webDriver =>
        clue(s"Logging in to wallet UI at: $validatorWalletUiUrl") {
          completeAuth0LoginWithAuthorization(
            validatorWalletUiUrl,
            validatorUserName,
            validatorUserPassword,
            // Notably, we expect that the service user is always already onboarded.
            () => find(id("wallet-balance-cc")) should not be empty,
          )
        }
        val recipient =
          PartyId.tryFromProtoPrimitive(seleniumText(find(id("logged-in-user"))))
        clue("Checking that balance is visible and > 0") {
          val balanceText = find(id("wallet-balance-cc")).value.text.trim
          (recipient, BigDecimal(balanceText.split(" ").head))
        }
    }
    if (balance <= BigDecimal(targetBalance)) {
      clue(
        s"Balance is too low, topping up $validatorName's wallet to have at least $targetBalance CC"
      ) {
        sv1TopupValidatorWallet(recipient, targetBalance - balance)
      }
      withFrontEnd("validator-user") { implicit webDriver =>
        val acceptButton = eventually() {
          findAll(className("transfer-offer")).toSeq.headOption match {
            case Some(element) =>
              element.childWebElement(className("transfer-offer-accept"))
            case None => fail("failed to find transfer offer")
          }
        }
        click on acceptButton
        eventually() {
          val balanceText = find(id("wallet-balance-cc")).value.text.trim
          val balance = BigDecimal(balanceText.split(" ").head)
          balance should be > BigDecimal(minimalBalance)
        }
      }
    }
  }

  private def sv1TopupValidatorWallet(recipient: PartyId, amount: BigDecimal): Unit = {
    withFrontEnd("sv1-user") { implicit webDriver =>
      clue(s"Logging in to SV1 wallet UI at: $sv1WalletUiUrl") {
        completeAuth0LoginWithAuthorization(
          sv1WalletUiUrl,
          sv1UserName,
          sv1UserPassword,
          () => find(id("wallet-balance-cc")) should not be empty,
        )
      }
      createTransferOffer(recipient, amount, 1)
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
