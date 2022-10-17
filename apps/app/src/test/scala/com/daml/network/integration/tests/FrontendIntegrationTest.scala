package com.daml.network.integration.tests

import java.nio.file.Paths
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
import org.openqa.selenium.{OutputType, TakesScreenshot, WebDriver}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxDriverLogLevel, FirefoxOptions}
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.selenium.WebBrowser

import scala.collection.mutable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.io.File
import org.apache.commons.io.FileUtils

abstract class FrontendIntegrationTest(frontendNames: String*)
    extends CoinIntegrationTest
    with BeforeAndAfterEach
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences
    with WebBrowser {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  System.setProperty(
    FirefoxDriver.SystemProperty.BROWSER_LOGFILE,
    Paths.get("log", "browser.log").toString,
  )
  val options: FirefoxOptions =
    new FirefoxOptions().setHeadless(true).setLogLevel(FirefoxDriverLogLevel.DEBUG)

  protected val webDrivers: mutable.Map[String, WebDriver with TakesScreenshot] = mutable.Map.empty

  def withFrontEnd[A](driverName: String)(implicit f: WebDriver with TakesScreenshot => A): A =
    f(
      webDrivers
        .get(driverName)
        .getOrElse(
          sys.error(
            s"No such webDriver : $driverName. Did you forget to pass it to FrontendIntegrationTest?"
          )
        )
    )

  override def beforeEach() = {
    for { name <- frontendNames.toSeq } {
      val webDriver = new FirefoxDriver(options)
      webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5))
      webDrivers += (name -> webDriver)
    }
    super.beforeEach()
  }

  override def testFinished(env: CoinTestConsoleEnvironment): Unit = {
    // testFinished runs before afterEach and tears down all our apps.
    // Therefore, we need to check for errors here. Otherwise, we run
    // into issues where we get an error just by virtue of the gRPC
    // service being down.
    webDrivers.values.flatMap { implicit webDriver =>
      findAll(id("error")).toList.map(e => fail(s"Found unexpected error: ${e.text}"))
    }
    super.testFinished(env)
  }

  override def afterEach() = {
    super.afterEach()
    webDrivers.values.foreach(_.quit())
  }

  protected def consumeError(err: String)(implicit webDriver: WebDriver): Unit = {
    val text = inside(findAll(id("error")).toList) { case Seq(elem) => elem.text }
    text shouldBe err
    click on "clear-error-button"
  }

  /** Takes a screenshot of the current browser state, into a timestamped png file in log directory.
    * Currently intended only for manual use during development and debugging.
    */
  protected def screenshot()(implicit webDriver: WebDriver with TakesScreenshot): Unit = {
    val screenshotFile = webDriver.getScreenshotAs(OutputType.FILE)
    val time = Calendar.getInstance.getTime
    val timestamp = new SimpleDateFormat("yy-MM-dd-H:m:s.S").format(time)
    val filename = Paths.get("log", s"screenshot-${timestamp}.png").toString
    FileUtils.copyFile(screenshotFile, new File(filename))
  }
}
