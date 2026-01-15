package org.lfdecentralizedtrust.splice.integration.tests

import cats.syntax.either.*
import cats.syntax.parallel.*
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
  TestCommon,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.*
import org.apache.commons.io.FileUtils
import org.openqa.selenium.bidi.Event
import org.openqa.selenium.bidi.log.{Log, LogEntry, LogLevel}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxDriverLogLevel, FirefoxOptions}
import org.openqa.selenium.{
  By,
  JavascriptExecutor,
  OutputType,
  TakesScreenshot,
  WebDriver,
  WebDriverException,
  WebElement,
}
import org.openqa.selenium.html5.WebStorage
import org.openqa.selenium.json.{Json, JsonInput}
import org.openqa.selenium.support.ui.{ExpectedCondition, ExpectedConditions, WebDriverWait}
import org.scalatest.{Assertion, ParallelTestExecution}
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
import scala.jdk.DurationConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.matching.Regex

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

  class TextMixedWithNumbersMatcher(pattern: Regex, values: Seq[BigDecimal], tolerance: BigDecimal)
      extends Matcher[String] {
    def apply(left: String): MatchResult = {
      val rightAsText =
        s"'${pattern}' with values ${values.mkString("[", ", ", "]")} (±${tolerance})"
      pattern
        .findFirstMatchIn(left)
        .fold(
          MatchResult(false, s"'$left' did not match $rightAsText: pattern does not match", "")
        )(m => {
          require(m.subgroups.length == values.length)
          val errors = m.subgroups
            .zip(values)
            .foldLeft(Seq.empty[String]) { case (result, (subgroup, expectedAmount)) =>
              Try(BigDecimal(subgroup)).fold(
                _ => result :+ s"$subgroup is not a number",
                actualAmount =>
                  if ((actualAmount - expectedAmount).abs > tolerance)
                    result :+ s"$actualAmount was not $expectedAmount±${tolerance}"
                  else result,
              )
            }
          MatchResult(
            errors.isEmpty,
            s"'$left' did not match $rightAsText: ${errors.mkString(", ")}",
            s"'$left' did match $rightAsText",
          )
        })
    }
  }

  def matchTextMixedWithNumbers(pattern: Regex, values: Seq[BigDecimal], tolerance: BigDecimal) =
    new TextMixedWithNumbersMatcher(pattern, values, tolerance)
}

abstract class FrontendIntegrationTest(override val frontendNames: String*)
    extends IntegrationTest
    with FrontendTestCommon
    with ParallelTestExecution {

  override def provideEnvironment(testName: String) = {
    val env = super.provideEnvironment(testName)
    // We need to start the web frontends afterwards because selenium
    // insists on automatically selecting free ports and those may
    // collide with the ports used by our own services.
    if (autostartWebDrivers) {
      startWebDrivers()
    }
    env
  }

  override def testFinished(testName: String, env: SpliceTestConsoleEnvironment): Unit = {
    // testFinished runs before afterEach and tears down all our apps.
    // Therefore, we stop the web browsers here so we don't get log warnings
    // because the frontends fail to connect to backends.
    stopWebDrivers(env.executionContext)
    super.testFinished(testName, env)
  }
}

abstract class FrontendIntegrationTestWithSharedEnvironment(override val frontendNames: String*)
    extends IntegrationTestWithSharedEnvironment
    with FrontendTestCommon {

  override def beforeAll() = {
    super.beforeAll()
    if (autostartWebDrivers) {
      startWebDrivers()
    }
  }

  override def testFinished(testName: String, env: SpliceTestConsoleEnvironment): Unit = {
    clearWebDrivers(provideEnvironment(testName).executionContext)
    super.testFinished(testName, env)
  }

  override def afterAll(): Unit = {
    try {
      stopWebDrivers(provideEnvironment("NotUsed").executionContext)
    } finally super.afterAll()
  }
}

trait FrontendTestCommon extends TestCommon with WebBrowser with CustomMatchers {

  protected lazy val autostartWebDrivers: Boolean = true

  import FrontendIntegrationTest.NavigationInfo

  val frontendNames: Seq[String] = Seq()

  type WebDriverType = WebDriver with TakesScreenshot with JavascriptExecutor with WebStorage

  val aliceWalletUIPort = 3000
  val bobWalletUIPort = 3001
  val charlieWalletUIPort = 3002

  val aliceAnsUIPort = 3100

  val sv1UIPort = 3211
  val sv2UIPort = 3212

  val scanUIPort = 3311

  val aliceSplitwellUIPort = 3400
  val bobSplitwellUIPort = 3401
  val charlieSplitwellUIPort = 3402
  val splitwellSplitwellUIPort = 3420

  val options: FirefoxOptions =
    new FirefoxOptions()
      .setLogLevel(FirefoxDriverLogLevel.DEBUG)
      .addArguments("-headless")
  options.setCapability("webSocketUrl", true: Any);

  protected val webDrivers: mutable.Map[String, WebDriverType] = mutable.Map.empty
  private def registerWebDriver(name: String, webDriver: WebDriverType): Unit = blocking {
    synchronized {
      webDrivers += (name -> webDriver)
    }
  }

  private def traceContextFromConsoleMessage(msg: String): TraceContext = {
    // If available, frontend console log messages apppend the W3C trace parent value at the end
    // of the message. See OpenApiLoggingMiddleware.ts.
    val traceParentRegEx =
      raw".*\[([0-9a-zA-Z]{2}-[0-9a-zA-Z]{32}-[0-9a-zA-Z]{16}-[0-9a-zA-Z]{2})\]$$".r
    msg match {
      case traceParentRegEx(traceParent) => TraceContext.fromW3CTraceParent(traceParent)
      case _ => traceContext
    }
  }

  protected def startWebDriver(name: String) = {
    if (!frontendNames.contains(name)) {
      throw new IllegalArgumentException(s"$name was not in defined frontends $frontendNames")
    }
    val logger = loggerFactory.append("web-frontend", name).getTracedLogger(getClass)
    logger.info(s"Starting web-frontend")
    val logFileName =
      s"browser.${this.getClass.getName}.${name}.${FrontendIntegrationTest.counter.getAndIncrement()}"

    var attempt = 0
    val (webDriver, biDi) = eventually(timeUntilSuccess = 40.seconds) {
      attempt += 1
      logger.info(s"Attempting to start FirefoxDriver for $name...")

      val browserLogFile = Paths.get("log", s"$logFileName.$attempt.log").toFile
      val driver =
        Try {
          val builder = new GeckoDriverService.Builder()
            .withLogFile(browserLogFile)
            // Specify the driver executable explicitly, this is important to avoid it checking for new versions which can result in log warnings like this:
            // The geckodriver version (0.35.0) detected in PATH at /nix/store/… might not be compatible with the detected firefox version
            .usingDriverExecutable(
              new java.io.File(
                sys.env
                  .get("GECKODRIVER")
                  .getOrElse(
                    sys.error(
                      "GECKODRIVER environment variable was not set, this should be set through nix/direnv so check your setup"
                    )
                  )
              )
            );
          new FirefoxDriver(builder.build(), options)
        }.toEither.valueOr { e =>
          logger.info(s"FirefoxDriver failed to start ($name); retrying. The error was: $e")
          fail()
        }

      logger.debug(s"FirefoxDriver started, attempting to get BiDi connection... ($name)")
      val bidi = Try(driver.getBiDi()).toEither.valueOr { e =>
        logger.info(s"Failed to get BiDi connection to FirefoxDriver ($name). The error was $e")
        Try(driver.quit()).leftSide.foreach(e => logger.debug("Driver failed to quit properly.", e))
        fail()
      }

      logger.debug(s"FirefoxDriver started and BiDi connection acquired ($name)")
      logger.debug(
        s"windowHandle='${driver.getWindowHandle}', currentUrl='${driver.getCurrentUrl}', sessionId='${driver.getSessionId.toString}' ($name)"
      )
      (driver, bidi)
    }
    webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(0))
    registerWebDriver(name, webDriver)

    biDi.addListener[LogEntry](
      Log.entryAdded(),
      logEntry => {
        logEntry.getConsoleLogEntry.toScala.foreach { consoleLogEntry =>
          val msg = consoleLogEntry.getText
          val ctx = traceContextFromConsoleMessage(msg)
          consoleLogEntry.getLevel match {
            case LogLevel.DEBUG => logger.debug(msg)(ctx)
            case LogLevel.INFO => logger.info(msg)(ctx)
            case LogLevel.WARNING => logger.warn(msg)(ctx)
            case LogLevel.ERROR => logger.error(msg)(ctx)
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
    eventually(60.seconds) {
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
    }
    logger.info("Cleared web drivers")
  }

  def withFrontEnd[A](driverName: String)(implicit f: WebDriverType => A): A = {
    val webDriver = webDrivers
      .get(driverName)
      .getOrElse(
        sys.error(
          s"No such webDriver : $driverName. Did you forget to pass it to FrontendIntegrationTest?"
        )
      )
    dumpDebugInfoOnFailure(f(webDriver))(webDriver)
  }

  /** The `<TextInput>` in ts code is converted by react into a deep tree. This returns the input field. */
  protected def reactTextInput(textField: Element): TextField = new TextField(
    textField.childElement(className("MuiInputBase-input")).underlying
  )

  protected def seleniumText(elementOpt: Option[Element]): String =
    elementOpt
      .map(el => el.attribute("data-selenium-text").getOrElse(el.text))
      .getOrElse("")

  protected def seleniumText(element: Element): String = seleniumText(Some(element))

  protected def matchRow(
      dataClassNames: Seq[String],
      expectedValues: Seq[String],
  )(row: Element): Unit = {
    if (dataClassNames.length !== expectedValues.length) {
      fail("className list must match length of expected value list")
    } else {
      dataClassNames
        .map(name => seleniumText(row.childElement(className(name))))
        .zip(expectedValues)
        .foreach({
          case (dataText, expectedValue) => {
            dataText should matchText(expectedValue)
          }
        })
    }
  }

  /** Specialized version of eventually() for frontend tests.
    *
    * super.eventually() only retries on TestFailedException, thrown for example by in "foo should be(1)".
    * In frontend tests, we want to additionally retry every failure related to Selenium,
    * i.e., any subclass of WebDriverException.
    *
    * It's the responsibility of the caller to make sure the test code wrapped in eventually() is idempotent.
    */
  override def eventually[T](
      timeUntilSuccess: FiniteDuration = 20.seconds,
      maxPollInterval: FiniteDuration = 100.millis,
      retryOnTestFailuresOnly: Boolean = true,
  )(testCode: => T): T =
    super.eventually(timeUntilSuccess, maxPollInterval, retryOnTestFailuresOnly) {
      try {
        testCode
      } catch {
        // Some of WebDriverException subclasses represent non-retryable errors, we rely on the timeout
        // to avoid blocking indefinitely.
        // Of particular interest is StaleElementReferenceException, which usually happens when the test
        // is interacting with the web page while the web page is being redrawn. Often these redraws
        // can be optimized away in React, so we record them here.
        case e: WebDriverException =>
          logger.debug(
            s"Retrying ${e.getClass.getSimpleName}\n${e.getStackTrace.map("  at " + _.toString).mkString("\n")}"
          )(TraceContext.empty)
          fail(e)
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

  protected def waitForCondition(
      query: Query,
      timeUntilSuccess: Option[FiniteDuration] = None,
  )(condition: By => ExpectedCondition[WebElement])(implicit
      webDriver: WebDriver
  ): Unit = {
    waitForQuery(query, timeUntilSuccess)

    val waitDuration = timeUntilSuccess.getOrElse(20.seconds)
    val wait = new WebDriverWait(webDriver, waitDuration.toJava);

    eventually(waitDuration) {
      wait.until(condition(query.by))
    }
  }

  protected def consumeError(err: String)(implicit webDriver: WebDriver): Unit = {
    find(id("error")).value.text should include(err)
    eventuallyClickOn(id("clear-error-button"))
    eventually() {
      find(id("error")) shouldBe None
    }
  }

  /** Takes a screenshot of the current browser state, into a timestamped png file in log directory.
    * Currently intended only for manual use during development and debugging.
    */
  protected def screenshot()(implicit webDriver: WebDriverType): Unit = {
    clue("Saving screenshot") {
      val fullScreen = webDriver.findElement(By.tagName("body"))
      val screenshotFile = fullScreen.getScreenshotAs(OutputType.FILE)
      val time = Calendar.getInstance.getTime
      val timestamp = new SimpleDateFormat("yy-MM-dd-H:m:s.S").format(time)
      val filename = Paths.get("log", s"screenshot-${timestamp}.png").toString
      FileUtils.copyFile(screenshotFile, new File(filename))
    }
  }

  /** Saves the current web page (the currently rendered DOM) to a file in the log directory. */
  protected def savePageSource()(implicit webDriver: WebDriverType): Unit = {
    clue("Saving page source") {
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
  }

  /** Returns a list of network requests performed by the frontend since the beginning of the test.
    * The result is JSON-encoded and human readable.
    * Currently intended only for manual use during development and debugging.
    *
    * Note that the Resource Timing API only returns timing information, it does not contain the status
    * of requests (e.g., HTTP 200 or HTTP 404).
    */
  protected def logNetworkRequests()(implicit webDriver: WebDriverType): Unit = {
    clue("Logging network requests") {
      Try(
        webDriver
          .executeScript(
            "performance.getEntriesByType(\"resource\").forEach(e => console.debug(JSON.stringify(e, undefined, \"  \")))"
          )
      ).fold(e => logger.debug(s"Failed to get network requests: $e"), _ => ())
    }
  }

  protected def logCookies()(implicit webDriver: WebDriverType): Unit = {
    clue("Logging cookies") {
      // Note: only logging cookie names, as values might contain sensitive information
      Try {
        val cookies = webDriver
          .manage()
          .getCookies
          .asScala
        logger.debug(cookies.map(c => s"  ${c.getName}").mkString("\n"))
      }.fold(e => logger.debug(s"Failed to log cookies: $e"), _ => ())
    }
  }

  protected def logLocalAndSessionStorage()(implicit webDriver: WebDriverType): Unit = {
    clue("Logging local and session storage") {
      // Note: only logging keys, as values might contain sensitive information
      Try {
        val localStorageKeys = webDriver.getLocalStorage.keySet.asScala
        logger.debug(s"localStorage:\n${localStorageKeys.mkString("\n")}")
        val sessionStorageKeys = webDriver.getSessionStorage.keySet.asScala
        logger.debug(s"sessionStorage:\n${sessionStorageKeys.mkString("\n")}")
      }.fold(e => logger.debug(s"Failed to log storage: $e"), _ => ())
    }
  }

  protected def dumpDebugInfoOnFailure[T](value: => T)(implicit
      webDriver: WebDriverType
  ): T = {
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
          logCookies()
          logLocalAndSessionStorage()
        }
        throw e
    }
  }

  private def assertAuth0LoginFormVisible()(implicit
      webDriver: WebDriverType
  ) = {
    find(tagName("h1")) should not be None
    find(tagName("h1")).value.text shouldBe "Welcome"
    find(cssSelector("div.password")) should not be empty
  }

  private def assertAuth0AuthorizationFormVisible()(implicit
      webDriver: WebDriverType
  ) = {
    find(tagName("h1")) should not be None
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
    eventually(1.minutes) {
      try {
        dumpDebugInfoOnFailure {
          silentActAndCheck(
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
            silentActAndCheck(
              "Auth0 login: Log out",
              eventuallyClickOn(id("logout-button")),
            )(
              "Auth0 login: Login button is visible",
              _ => find(id("oidc-login-button")) should not be empty,
            )
          }

          loginViaAuth0InCurrentPage(username, password, assertCompleted)
        }
      } catch {
        case NonFatal(e) =>
          // If the login fails, the web page might be stuck in a bad state.

          // First, delete cookies related to auth0
          // Note: the web driver API only allows you to delete cookies associated with the current page.
          clue("Clearing cookies") {
            val auth0cookies =
              webDriver.manage().getCookies.asScala.filter(_.getDomain.contains("auth0"))
            logger.debug(
              s"Deleting the following cookies: ${auth0cookies.map(_.getName)}"
            )
            auth0cookies.foreach(webDriver.manage().deleteCookie)
          }

          // Next, clear local storage, as our oauth library puts data in there
          // Note: again, you can only access the local storage of the current page.
          clue("Clearing local storage") {
            val auth0LocalState =
              webDriver.getLocalStorage.keySet.asScala.filter(_.startsWith("oidc."))
            logger.debug(
              s"Deleting the following keys from localStorage: $auth0LocalState"
            )
            auth0LocalState.foreach(webDriver.getSessionStorage.removeItem)
          }
          // Finally, navigate away from the current page to clear any JavaScript state
          go to "about:blank"
          throw e
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
    silentActAndCheck(
      "Auth0 login: Click the login button",
      eventuallyClickOn(id("oidc-login-button")),
    )(
      "Auth0 login: Login form is visible",
      _ => assertAuth0LoginFormVisible(),
    )

    val (_, needsAuthorization) = silentActAndCheck(
      "Auth0 login: Fill out and submit login form", {
        emailField(id("username")).value = username
        find(id("password")).foreach(_.underlying.sendKeys(password))
        inside(findAll(name("action")).filter(_.text == "Continue").filter(_.isDisplayed).toSeq) {
          case Seq(button) =>
            click on button
        }
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
      silentActAndCheck(
        "Auth0 login: Give consent",
        click on xpath("//button[@value='accept']"),
      )(
        "Auth0 login: Target page is visible",
        _ => assertCompleted(),
      )
    }
  }

  protected def setAnsField(textField: TextField, input: String, expectedPartyId: String) = {
    textField.underlying.sendKeys(input)
    // We need to wait for the query against the ans service to finish.
    waitForAnsField(textField, expectedPartyId)
  }

  protected def waitForAnsField(textField: TextField, expectedPartyId: String) = {
    eventually() {
      textField.attribute("data-resolved-party-id") shouldBe Some(expectedPartyId)
    }
  }

  protected def eventuallyClickOn(query: Query)(implicit driver: WebDriver) = {
    clue(s"Waiting for $query to be clickable") {
      waitForCondition(query) {
        ExpectedConditions.elementToBeClickable(_)
      }
    }
    clickOn(query)
  }

  protected def clickByCssSelector(selector :String) (
    implicit webDriver : WebDriver
  ): Unit = {
    val query = cssSelector(selector)
    waitForCondition(query) {
      ExpectedConditions.elementToBeClickable(_)
    }
    eventuallyClickOn(query)
  }

  protected def eventuallyFind(query: Query)(implicit driver: WebDriver) = {
    clue(s"Waiting for $query to be found") {
      waitForCondition(query) {
        ExpectedConditions.visibilityOfElementLocated(_)
      }
    }
    find(query)
  }

  def setDateTime(party: String, pickerId: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ): Assertion = {
    clue(s"$party selects the date $dateTime") {
      val dateTimePicker = webDriver.findElement(By.id(pickerId))
      eventually() {
        dateTimePicker.clear()
        dateTimePicker.click()
        // Typing in the "filler" characters can mess up the input badly
        // Note: this breaks on Feb 29th because the date library validates that the day
        // of the month is valid for the year you enter and because the year is entered
        // one digit at a time that fails and it resets it to Feb 28th. Luckily,
        // this does not happen very often …
        dateTimePicker.sendKeys(dateTime.replaceAll("[^0-9APM]", ""))
        eventually()(
          dateTimePicker.getAttribute("value").toLowerCase shouldBe dateTime.toLowerCase
        )
      }
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
