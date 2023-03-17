package com.daml.network.integration.tests

import cats.syntax.either.*
import cats.syntax.parallel.*
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinIntegrationTestWithSharedEnvironment,
  CoinTestCommon,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.util.FutureInstances.*
import org.apache.commons.io.FileUtils
import org.openqa.selenium.bidi.Event
import org.openqa.selenium.bidi.log.{Log, LogLevel, LogEntry}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxDriverLogLevel, FirefoxOptions}
import org.openqa.selenium.{
  JavascriptExecutor,
  OutputType,
  StaleElementReferenceException,
  TakesScreenshot,
  WebDriver,
}
import org.openqa.selenium.html5.WebStorage
import org.openqa.selenium.json.{Json, JsonInput}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatestplus.selenium.WebBrowser

import java.io.{File, StringReader}
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import org.openqa.selenium.firefox.GeckoDriverService
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
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

abstract class FrontendIntegrationTest(override val frontendNames: String*)
    extends CoinIntegrationTest
    with FrontendTestCommon
    with BeforeAndAfterEach {

  override def provideEnvironment = {
    val env = super.provideEnvironment
    // We need to start the web frontends afterwards because selenium
    // insists on automatically selecting free ports and those may
    // collide with the ports used by our own services.
    if (autostartWebDrivers) {
      startWebDrivers(env.executionContext)
    }
    env
  }

  override def testFinished(env: CoinTestConsoleEnvironment): Unit = {
    // testFinished runs before afterEach and tears down all our apps.
    // Therefore, we stop the web browsers here so we don't get log warnings
    // because the frontends fail to connect to backends.
    stopWebDrivers(env.executionContext)
    super.testFinished(env)
  }
}

abstract class FrontendIntegrationTestWithSharedEnvironment(override val frontendNames: String*)
    extends CoinIntegrationTestWithSharedEnvironment
    with FrontendTestCommon {

  override def beforeAll() = {
    super.beforeAll()
    if (autostartWebDrivers) {
      startWebDrivers(provideEnvironment.executionContext)
    }
  }

  override def testFinished(env: CoinTestConsoleEnvironment): Unit = {
    clearWebDrivers(provideEnvironment.executionContext)
    super.testFinished(env)
  }

  override def afterAll(): Unit = {
    try {
      stopWebDrivers(provideEnvironment.executionContext)
    } finally super.afterAll()
  }
}

trait FrontendTestCommon extends CoinTestCommon with WebBrowser with CustomMatchers {

  protected lazy val autostartWebDrivers: Boolean = true

  import FrontendIntegrationTest.NavigationInfo

  val frontendNames: Seq[String] = Seq()

  type WebDriverType = WebDriver with TakesScreenshot with JavascriptExecutor with WebStorage

  val options: FirefoxOptions =
    new FirefoxOptions().setLogLevel(FirefoxDriverLogLevel.DEBUG).addArguments("-headless")
  options.setCapability("webSocketUrl", true: Any);

  protected val webDrivers: mutable.Map[String, WebDriverType] = mutable.Map.empty

  protected def startWebDriver(name: String) = {
    if (!frontendNames.contains(name)) {
      throw new IllegalArgumentException(s"$name was not in defined frontends $frontendNames")
    }
    val logger = loggerFactory.append("web-frontend", name).getLogger(getClass)
    logger.info(s"Starting web-frontend")
    val browserLogFile =
      Paths
        .get(
          "log",
          s"browser.${this.getClass.getName}.${name}.${FrontendIntegrationTest.counter.getAndIncrement()}.log",
        )
        .toFile
    val (webDriver, biDi) = eventually() {
      val driver =
        Try {
          val builder = new GeckoDriverService.Builder().withLogFile(browserLogFile)
          new FirefoxDriver(builder.build(), options)
        }.toEither.valueOr { e =>
          logger.info(s"FirefoxDriver failed to start; retrying. The error was: $e")
          fail()
        }
      val bidi = Try(driver.getBiDi()).toEither.valueOr { e =>
        logger.info(s"Failed to get BiDi connection to web driver. The error was $e")
        driver.quit()
        fail()
      }
      (driver, bidi)
    }
    webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5))
    webDrivers += (name -> webDriver)
    biDi.addListener[LogEntry](
      Log.entryAdded(),
      logEntry => {
        logEntry.getConsoleLogEntry.toScala.foreach { consoleLogEntry =>
          val msg = consoleLogEntry.getText
          consoleLogEntry.getLevel match {
            case LogLevel.DEBUG => logger.debug(msg)
            case LogLevel.INFO => logger.info(msg)
            case LogLevel.WARNING => logger.warn(msg)
            case LogLevel.ERROR => logger.error(msg)
          }
        }
      },
    );
    val JSON = new Json()
    biDi.addListener[NavigationInfo](
      new Event[NavigationInfo](
        "browsingContext.domContentLoaded",
        params => {
          val reader = new StringReader(JSON.toJson(params))
          val input = JSON.newInput(reader)
          NavigationInfo.fromJson(input)
        },
      ),
      navigationInfo => logger.debug(s"dom content loaded for ${navigationInfo.url}"),
    );
  }

  protected def startWebDrivers(implicit ec: ExecutionContext) = {
    logger.info("Starting web drivers")
    // We start all browsers in parallel, for faster test init.
    frontendNames.toList.parTraverse { name =>
      Future {
        startWebDriver(name)
      }
    }.futureValue
    logger.info("Started web drivers")
  }

  protected def stopWebDrivers(implicit ec: ExecutionContext) = {
    logger.info("Stopping web drivers")
    // We process all browsers in parallel, for faster test termination.
    webDrivers.values.toList.parTraverse { webDriver =>
      Future {
        webDriver.quit()
      }
    }.futureValue
    logger.info("Stopped web drivers")
  }

  protected def clearWebDrivers(implicit ec: ExecutionContext) = {
    logger.info("Clearing web drivers")
    webDrivers.values.toList.parTraverse { implicit webDriver =>
      Future {
        // Reset session storage so we see the login window again.
        // You cannot reset session storage of about:blank so
        // we exclude this.
        if (currentUrl != "about:blank") {
          webDriver.getSessionStorage().clear()
          eventually() {
            webDriver.getSessionStorage().keySet.asScala shouldBe empty
          }
        }
      }
    }.futureValue
    logger.info("Cleared web drivers")
  }

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

  /** The `<TextInput>` in ts code is converted by react into a deep tree. This returns the input field. */
  protected def reactTextInput(textField: Element): TextField = new TextField(
    textField.childElement(className("MuiInputBase-input")).underlying
  )

  // We override eventually to also retry on StaleElementReferenceException
  // because that’s very common in frontend tests and having
  // each call site try to catch that seems unlikely to be reliable.
  override def eventually[T](
      timeUntilSuccess: FiniteDuration = 20.seconds,
      maxPollInterval: FiniteDuration = 5.seconds,
  )(testCode: => T): T = super.eventually(timeUntilSuccess, maxPollInterval) {
    try {
      testCode
    } catch {
      case e: StaleElementReferenceException => fail(e)
    }
  }

  protected def waitForQuery(query: Query, timeUntilSuccess: Option[FiniteDuration] = None)(implicit
      webDriver: WebDriver
  ): Unit = {
    timeUntilSuccess match {
      case None =>
        eventually() {
          find(query).valueOrFail(s"Could not find $query")
        }
      case Some(timeUntilSuccess) =>
        eventually(timeUntilSuccess) {
          find(query).valueOrFail(s"Could not find $query")
        }
    }

  }

  protected def consumeError(err: String)(implicit webDriver: WebDriver): Unit = {
    find(id("error")).value.text should include(err)
    click on "clear-error-button"
    eventually() {
      find(id("error")) shouldBe None
    }
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

  protected def completeAuth0Login(username: String, password: String)(implicit
      webDriver: WebDriver
  ) = {
    clue("Auth0 login") {
      textField(id("username")).value = username
      find(id("password")).foreach(_.underlying.sendKeys(password))
      click on name("action") // complete password prompt
    }
  }

  protected def completeAuth0LoginWithAuthorization(username: String, password: String)(implicit
      webDriver: WebDriver
  ) = {
    completeAuth0Login(username, password)
    completeOptionalAuth0Authorization(() => false)
  }

  /* Works independently of which prompts Auth0 shows you exactly.
   * Use this if you want it to just work. */
  protected def completeAuth0Prompts(
      username: String,
      password: String,
      completedWhen: () => Boolean,
  )(implicit
      webDriver: WebDriver
  ) = {
    completeOptionalAuth0Login(username, password, completedWhen)
    completeOptionalAuth0Authorization(completedWhen)
  }

  private def completeOptionalAuth0Login(
      username: String,
      password: String,
      completedWhen: () => Boolean,
  )(implicit
      webDriver: WebDriver
  ) = {
    eventually()(
      if (!completedWhen())
        completeAuth0Login(username, password)
    )
  }

  private def completeOptionalAuth0Authorization(
      completedWhen: () => Boolean
  )(implicit webDriver: WebDriver) = {
    clue("Auth0 authorization") {
      // the authorization prompt takes a while to appear sometimes
      eventually()(if (!completedWhen()) click on xpath("//button[@value='accept']"))
    }
  }

  protected def setDirectoryField(textField: TextField, input: String, expectedPartyId: String) = {
    textField.underlying.sendKeys(input)
    // We need to wait for the query against the directory service to finish.
    waitForDirectoryField(textField, expectedPartyId)
  }

  protected def waitForDirectoryField(textField: TextField, expectedPartyId: String) = {
    eventually() {
      textField.attribute("data-resolved-party-id") shouldBe Some(expectedPartyId)
    }
  }

}

object FrontendIntegrationTest {
  class NavigationInfo(
      val context: String,
      val navigation: Option[String],
      val timestamp: Long,
      val url: String,
  )
  object NavigationInfo {
    @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
    // Implemented using the selenium APIs so we can potentially upstream this.
    def fromJson(input: JsonInput): NavigationInfo = {
      var context: String = ""
      var navigation: Option[String] = None
      var timestamp: Long = 0
      var url: String = ""
      input.beginObject()
      while (input.hasNext()) {
        input.nextName() match {
          case "context" => {
            context = input.read[String](classOf[String])
          }
          case "navigation" => {
            navigation = Option(input.read[String](classOf[String]))
          }
          case "timestamp" => {
            timestamp = input.read[Long](classOf[Long])
          }
          case "url" => {
            url = input.read[String](classOf[String])
          }
        }
      }
      input.endObject()
      new NavigationInfo(context, navigation, timestamp, url)
    }
  }
  // counter to generate unique log-file names as otherwise each new test overwrites the previous one's log
  val counter = new AtomicLong(0)
}
