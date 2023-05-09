package com.daml.network.integration.tests

import cats.syntax.either.*
import cats.syntax.parallel.*
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.util.FutureInstances.*
import org.apache.commons.io.FileUtils
import org.openqa.selenium.bidi.Event
import org.openqa.selenium.bidi.log.{Log, LogEntry, LogLevel}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxDriverLogLevel, FirefoxOptions}
import org.openqa.selenium.{
  By,
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
import scala.concurrent.blocking
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

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
    extends CNNodeIntegrationTest
    with FrontendTestCommon
    with BeforeAndAfterEach {

  override def provideEnvironment = {
    val env = super.provideEnvironment
    // We need to start the web frontends afterwards because selenium
    // insists on automatically selecting free ports and those may
    // collide with the ports used by our own services.
    if (autostartWebDrivers) {
      startWebDrivers()
    }
    env
  }

  override def testFinished(env: CNNodeTestConsoleEnvironment): Unit = {
    // testFinished runs before afterEach and tears down all our apps.
    // Therefore, we stop the web browsers here so we don't get log warnings
    // because the frontends fail to connect to backends.
    stopWebDrivers(env.executionContext)
    super.testFinished(env)
  }
}

abstract class FrontendIntegrationTestWithSharedEnvironment(override val frontendNames: String*)
    extends CNNodeIntegrationTestWithSharedEnvironment
    with FrontendTestCommon {

  override def beforeAll() = {
    super.beforeAll()
    if (autostartWebDrivers) {
      startWebDrivers()
    }
  }

  override def testFinished(env: CNNodeTestConsoleEnvironment): Unit = {
    clearWebDrivers(provideEnvironment.executionContext)
    super.testFinished(env)
  }

  override def afterAll(): Unit = {
    try {
      stopWebDrivers(provideEnvironment.executionContext)
    } finally super.afterAll()
  }
}

trait FrontendTestCommon extends CNNodeTestCommon with WebBrowser with CustomMatchers {

  protected lazy val autostartWebDrivers: Boolean = true

  import FrontendIntegrationTest.NavigationInfo

  val frontendNames: Seq[String] = Seq()

  type WebDriverType = WebDriver with TakesScreenshot with JavascriptExecutor with WebStorage

  val options: FirefoxOptions =
    new FirefoxOptions()
      .setLogLevel(FirefoxDriverLogLevel.DEBUG)
      .addArguments("-headless", "--log-no-truncate")
  options.setCapability("webSocketUrl", true: Any);

  protected val webDrivers: mutable.Map[String, WebDriverType] = mutable.Map.empty
  private def registerWebDriver(name: String, webDriver: WebDriverType): Unit = blocking {
    synchronized {
      webDrivers += (name -> webDriver)
    }
  }

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

    val (webDriver, biDi) = eventually(timeUntilSuccess = 40.seconds) {
      logger.info(s"Attempting to start FirefoxDriver for $name...")

      val driver =
        Try {
          val builder = new GeckoDriverService.Builder().withLogFile(browserLogFile)
          new FirefoxDriver(builder.build(), options)
        }.toEither.valueOr { e =>
          logger.info(s"FirefoxDriver failed to start ($name); retrying. The error was: $e")
          fail()
        }
      logger.debug(s"FirefoxDriver started ($name)")
      val bidi = Try(eventually() {
        logger.debug(s"Attempting to open BiDi connection for FirefoxDriver ($name)")
        Try(driver.getBiDi()).toEither.valueOr { e =>
          logger.info(s"Failed to get BiDi connection to FirefoxDriver ($name). The error was $e")
          fail()
        }
      }).toEither.valueOr { e =>
        logger.info(
          s"Failed to get BiDi connection to FirefoxDriver, even after retries ($name). The error was $e"
        )
        driver.quit()
        fail()
      }
      logger.debug(s"FirefoxDriver started and BiDi connection acquired ($name)")
      (driver, bidi)
    }
    webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5))
    registerWebDriver(name, webDriver)
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

  protected def startWebDrivers() = {
    logger.info("Starting web drivers")

    // We start browsers in serial. This is done to allow reliable
    // operation of the free port discovery used to assign BiDI ports
    // to browsers. When starting browsers in parallel, we've observed
    // races that result in two browsers attempting to start with the
    // same BiDi port, with the second browser failing.
    frontendNames.toList.foreach { name =>
      startWebDriver(name)
    }
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

  /** Saves the current web page (the currently rendered DOM) to a file in the log directory. */
  protected def savePageSource()(implicit webDriver: WebDriverType): Unit = {
    val time = Calendar.getInstance.getTime
    val timestamp = new SimpleDateFormat("yy-MM-dd-H:m:s.S").format(time)
    val filename = Paths.get("log", s"webpage-${timestamp}.html").toString

    val pageSource =
      Try(webDriver.findElement(By.tagName("body")).getAttribute("outerHTML")).recover {
        case e: Throwable => e.getMessage
      }.get
    FileUtils.writeStringToFile(
      new File(filename),
      pageSource,
      java.nio.charset.StandardCharsets.UTF_8,
    )
  }

  /** Returns a list of network requests performed by the frontend since the beginning of the test.
    * The result is JSON-encoded and human readable.
    * Currently intended only for manual use during development and debugging.
    *
    * Note that the Resource Timing API only returns timing information, it does not contain the status
    * of requests (e.g., HTTP 200 or HTTP 404).
    */
  protected def logNetworkRequests()(implicit webDriver: WebDriverType): Unit = {
    Try(
      webDriver
        .executeScript(
          "performance.getEntriesByType(\"resource\").forEach(e => console.debug(JSON.stringify(e, undefined, \"  \")))"
        )
    ).fold(e => logger.debug(s"Failed to get network requests: $e"), _ => ())
  }

  private def dumpDebugInfoOnFailure[T](value: => T)(implicit
      webDriver: WebDriverType
  ) = {
    try {
      value
    } catch {
      case NonFatal(e) =>
        logger.debug(
          s"Caught error $e, dumping all frontend debug info before retrying or failing"
        )
        clue("Dumping frontend debug info") {
          screenshot()
          savePageSource()
          logNetworkRequests()
        }
        throw e
    }
  }

  private def assertAuth0LoginFormVisible()(implicit
      webDriver: WebDriverType
  ) = {
    find(tagName("h1")).value.text shouldBe "Welcome"
    find(cssSelector("div.password")) should not be empty
  }

  private def assertAuth0AuthorizationFormVisible()(implicit
      webDriver: WebDriverType
  ) = {
    find(tagName("h1")).value.text shouldBe "Authorize App"
  }

  protected def completeAuth0LoginWithAuthorization(
      url: String,
      username: String,
      password: String,
      assertCompleted: () => org.scalatest.Assertion,
  )(implicit
      webDriver: WebDriverType
  ) = {
    // Sometimes the whole auth0 login workflow gets stuck for unknown reasons.
    // Therefore we retry the whole workflow and take screenshots on each failed attempt.
    eventually(timeUntilSuccess = 1.minutes) {
      dumpDebugInfoOnFailure {
        actAndCheck(
          "Auth0 login: Open target web page",
          go to url,
        )(
          "Auth0 login: Log in or log out buttons are visible",
          _ =>
            Seq(
              find(id("logout-button")),
              find(id("oidc-login-button")),
            ).flatten should have size 1,
        )

        if (find(id("logout-button")).isDefined) {
          actAndCheck(
            "Auth0 login: Log out",
            click on id("logout-button"),
          )(
            "Auth0 login: Login button is visible",
            _ => find(id("oidc-login-button")) should not be empty,
          )
        }

        loginViaAuth0InCurrentPage(username, password, assertCompleted)
      }
    }
  }

  protected def loginViaAuth0InCurrentPage(
      username: String,
      password: String,
      assertCompleted: () => org.scalatest.Assertion,
  )(implicit
      webDriver: WebDriverType
  ) = {
    actAndCheck(
      "Auth0 login: Click the login button",
      click on "oidc-login-button",
    )(
      "Auth0 login: Login form is visible",
      _ => assertAuth0LoginFormVisible(),
    )

    val (_, needsAuthorization) = actAndCheck(
      "Auth0 login: Fill out and submit login form", {
        textField(id("username")).value = username
        find(id("password")).foreach(_.underlying.sendKeys(password))
        click on name("action")
      },
    )(
      "Auth0 login: Target page or authorization form is visible",
      _ => {
        Try(false -> assertCompleted())
          .recoverWith(_ => Try(true -> assertAuth0AuthorizationFormVisible()))
          .get
          ._1
      },
    )

    if (needsAuthorization) {
      actAndCheck(
        "Auth0 login: Give consent",
        click on xpath("//button[@value='accept']"),
      )(
        "Auth0 login: Target page is visible",
        _ => assertCompleted(),
      )
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
