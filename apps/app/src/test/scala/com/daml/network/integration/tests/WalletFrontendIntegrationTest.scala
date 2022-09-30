package com.daml.network.integration.tests

import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.openqa.selenium.{By, WebDriver}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}
import org.scalatestplus.selenium.{WebBrowser}
import scala.concurrent.duration.DurationInt

// Assumes the UI is served and envoy proxy is running
class WalletFrontendIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences
    with WebBrowser {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withConnectedDomains()
      .withAllocatedValidatorUsers()

  // TODO(i711): try sending the log output to our logfiles instead of /dev/null
  System.setProperty(FirefoxDriver.SystemProperty.BROWSER_LOGFILE, "/dev/null")
  val options: FirefoxOptions = new FirefoxOptions().setHeadless(true)

  "A wallet UI" should {

    "allow tapping coins and then list the created coins" in { implicit env =>
      // TODO(i711): Refactor initialization and cleanup into something easier to reuse
      implicit val webDriver: WebDriver = new FirefoxDriver(options)
      try {
        val aliceDamlUser = aliceRemoteWallet.config.damlUser
        aliceValidator.onboardUser(aliceDamlUser)

        go to "http://localhost:3000"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on "tap-amount-field"
        textField("tap-amount-field").value = "15.0"
        click on "tap-button"
        eventually(scaled(5 seconds)) {
          webDriver.findElements(By className ("coins-table-row")).size() should be(1)
        }
        val row = webDriver.findElements(By className ("coins-table-row")).get(0)
        val quantity = row.findElement(By.className("coins-table-quantity"))
        quantity.getText should be("15.0000000000")
      } finally {
        webDriver.quit()
      }
    }
  }
}
