package com.daml.network.integration.tests

import cats.syntax.parallel.*
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.FutureInstances.*
import org.apache.commons.io.FileUtils
import org.openqa.selenium.bidi.log.{BaseLogEntry, Log, LogEntry}
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxDriverLogLevel, FirefoxOptions}
import org.openqa.selenium.{
  JavascriptExecutor,
  OutputType,
  StaleElementReferenceException,
  TakesScreenshot,
  WebDriver,
}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatestplus.selenium.WebBrowser

import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.*
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

abstract class FrontendIntegrationTest(frontendNames: String*)
    extends CoinIntegrationTest
    with BeforeAndAfterEach
    with WebBrowser
    with CustomMatchers {

  type WebDriverType = WebDriver with TakesScreenshot with JavascriptExecutor

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

  override def provideEnvironment = {
    val env = super.provideEnvironment
    // We need to start the web frontends afterwards because selenium
    // insists on automatically selecting free ports and those may
    // collide with the ports used by our own services.
    // We start all browsers in parallel, for faster test init.
    implicit val ec = env.executionContext;
    frontendNames.toList.parTraverse { name =>
      Future {
        System.setProperty(
          FirefoxDriver.SystemProperty.BROWSER_LOGFILE,
          Paths
            .get(
              "log",
              s"browser.${this.getClass.getName}.${name}.${FrontendIntegrationTest.counter.getAndIncrement()}.log",
            )
            .toString,
        )
        val logger = loggerFactory.append("web-frontend", name).getLogger(getClass)
        val webDriver = eventually() {
          try {
            new FirefoxDriver(options)
          } catch {
            case NonFatal(e) => {
              logger.info(s"FirefoxDriver failed to start; retrying. The error was: $e")
              fail()
            }
          }
        }
        webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5))
        webDrivers += (name -> webDriver)
        val biDi = webDriver.getBiDi();
        biDi.addListener[LogEntry](
          Log.entryAdded(),
          logEntry => {
            logEntry.getConsoleLogEntry.toScala.foreach { consoleLogEntry =>
              val msg = consoleLogEntry.getText
              consoleLogEntry.getLevel match {
                case BaseLogEntry.LogLevel.DEBUG => logger.debug(msg)
                case BaseLogEntry.LogLevel.INFO => logger.info(msg)
                case BaseLogEntry.LogLevel.WARNING => logger.warn(msg)
                case BaseLogEntry.LogLevel.ERROR => logger.error(msg)
              }
            }
          },
        );
      }
    }.futureValue
    env
  }

  override def testFinished(env: CoinTestConsoleEnvironment): Unit = {
    // testFinished runs before afterEach and tears down all our apps.
    // Therefore, we need to check for errors here. Otherwise, we run
    // into issues where we get an error just by virtue of the gRPC
    // service being down.
    // We process all browsers in parallel, for faster test termination.
    implicit val ec = env.executionContext;
    webDrivers.values.toList.parTraverse { implicit webDriver =>
      Future {
        // we optimistically wait only shortly, for faster test termination
        webDriver.manage().timeouts().implicitlyWait(Duration.ofMillis(100))
        findAll(id("error")).toList.map(e => fail(s"Found unexpected error: ${e.text}"))
        webDriver.quit()
      }
    }.futureValue
    super.testFinished(env)
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

  protected def browseToWallet(port: Int, damlUser: String)(implicit webDriver: WebDriver) = {
    actAndCheck(
      s"Browse to wallet UI at port ${port}", {
        go to s"http://localhost:${port}"
        click on "user-id-field"
        textField("user-id-field").value = damlUser
        click on "login-button"
      },
    )(
      "Logged in user shows up",
      _ => find(id("logged-in-user")).getOrElse(fail("Logged-in user information never showed up")),
    )
  }

  protected def browseToAliceWallet(damlUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3000, damlUser)
  }

  protected def browseToBobWallet(damlUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3001, damlUser)
  }

  protected def browseToPaymentRequests(damlUser: String)(implicit webDriver: WebDriver) = {
    // Go to app payment requests tab in alice's wallet
    browseToAliceWallet(damlUser)
    click on "app-payment-requests-button"
  }

  protected def browseToSubscriptions(damlUser: String)(implicit webDriver: WebDriver) = {
    // Go to subscriptions tab in alice's wallet
    browseToAliceWallet(damlUser)
    click on "subscriptions-button"
  }

}

object FrontendIntegrationTest {
  // counter to generate unique log-file names as otherwise each new test overwrites the previous one's log
  val counter = new AtomicLong(0)
}

trait CnsTestUtil {
  protected def expectedCns(partyId: PartyId, entry: String) = {
    s"${entry}\n(\n${partyId.toProtoPrimitive}\n)"
  }
}
