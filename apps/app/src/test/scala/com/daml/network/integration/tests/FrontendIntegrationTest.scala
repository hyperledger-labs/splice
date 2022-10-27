package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import org.apache.commons.io.FileUtils
import org.openqa.selenium.bidi.log.{Log, LogEntry}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxDriverLogLevel, FirefoxOptions}
import org.openqa.selenium.{JavascriptExecutor, OutputType, TakesScreenshot, WebDriver}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatestplus.selenium.WebBrowser

import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Calendar
import scala.collection.mutable
import scala.jdk.OptionConverters.*
import scala.util.Try

trait CustomMatchers {

  /** Matches strings while considering all whitespaces to be equal.
    * Useful when asserting a certain sentence in the web ui to be as expected,
    * independently of whitespace characters that are ignored during rendering.
    */
  class TextMatcher(sentence: String) extends Matcher[String] {
    def apply(left: String) =
      MatchResult(
        left.split("\\s+").sameElements(sentence.split("\\s+")),
        s"words in ${left} did not match those in ${sentence}",
        s"words in ${left} matched those in ${sentence}",
      )
  }
  def matchText(sentence: String) = new TextMatcher(sentence)
}

abstract class FrontendIntegrationTest(frontendNames: String*)
    extends CoinIntegrationTest
    with BeforeAndAfterEach
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences
    with WebBrowser
    with CustomMatchers {

  type WebDriverType = WebDriver with TakesScreenshot with JavascriptExecutor

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  val options: FirefoxOptions =
    new FirefoxOptions().setHeadless(true).setLogLevel(FirefoxDriverLogLevel.DEBUG)
  options.setCapability("webSocketUrl", true: Any);

  protected val webDrivers: mutable.Map[String, WebDriverType] = mutable.Map.empty

  def withFrontEnd[A](driverName: String)(implicit f: WebDriverType => A): A =
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
      System.setProperty(
        FirefoxDriver.SystemProperty.BROWSER_LOGFILE,
        Paths.get("log", s"browser.${this.getClass.getName}.${name}.log").toString,
      )
      val webDriver = new FirefoxDriver(options)
      webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5))
      webDrivers += (name -> webDriver)
      val biDi = webDriver.getBiDi();
      val logger = loggerFactory.append("web-frontend", name).getLogger(getClass)
      biDi.addListener[LogEntry](
        Log.entryAdded(),
        logEntry => {
          logEntry.getConsoleLogEntry.toScala.foreach { consoleLogEntry =>
            val msg = consoleLogEntry.getText
            // For some reason LogLevel is not exposed so casting it to Any
            // and calling toString on that and parsing that is apparently
            // the only option.
            (consoleLogEntry.getLevel: Any).toString match {
              case "debug" => logger.debug(msg)
              case "info" => logger.info(msg)
              case "warning" => logger.warn(msg)
              case "error" => logger.error(msg)
              case level => logger.error(s"Log message with unknown level `$level`: $msg")
            }
          }
        },
      );
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
  protected def screenshot()(implicit webDriver: WebDriverType): Unit = {
    val screenshotFile = webDriver.getScreenshotAs(OutputType.FILE)
    val time = Calendar.getInstance.getTime
    val timestamp = new SimpleDateFormat("yy-MM-dd-H:m:s.S").format(time)
    val filename = Paths.get("log", s"screenshot-${timestamp}.png").toString
    FileUtils.copyFile(screenshotFile, new File(filename))
  }

  /** Returns a list of network requests performed by the frontend since the beginning of the test.
    * The result is JSON-encoded and human readable.
    * Currently intended only for manual use during development and debugging.
    *
    * Note that the Resource Timing API only returns timing information, it does not contain the status
    * of requests (e.g., HTTP 200 or HTTP 404).
    */
  protected def getNetworkRequests()(implicit webDriver: WebDriverType): String = {
    Try(
      webDriver
        .executeScript("return JSON.stringify(performance.getEntriesByType(\"resource\"))")
        .toString
    ).fold(e => s"Failed to get network requests: $e", x => x)
  }

  protected def expectedCns(partyId: PartyId, entry: String) = {
    val full = partyId.toProtoPrimitive
    s"${entry} (${full.substring(0, 4)}...${full.substring(full.length - 4)})"
  }

}
