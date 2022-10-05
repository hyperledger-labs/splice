package com.daml.network.integration.tests

import java.time.Duration

import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.openqa.selenium.WebDriver
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.selenium.WebBrowser

trait FrontendIntegrationTest
    extends CoinIntegrationTest
    with BeforeAndAfterEach
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences
    with WebBrowser {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  // TODO(i711): try sending the log output to our logfiles instead of /dev/null
  System.setProperty(FirefoxDriver.SystemProperty.BROWSER_LOGFILE, "/dev/null")
  val options: FirefoxOptions = new FirefoxOptions().setHeadless(true)

  implicit var webDriver: WebDriver = _

  override def beforeEach() = {
    webDriver = new FirefoxDriver(options)
    webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5))
    super.beforeEach()
  }

  override def afterEach() = {
    super.afterEach()
    webDriver.quit()
  }
}
