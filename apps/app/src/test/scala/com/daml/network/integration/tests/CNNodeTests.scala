package com.daml.network.integration.tests

import org.apache.pekko.actor.{ActorSystem, CoordinatedShutdown}
import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.Http
import com.auth0.exception.Auth0Exception
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.auth.AuthUtil
import com.daml.network.config.AuthTokenSourceConfig
import com.daml.network.console.*
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.WaitForPorts
import com.daml.network.sv.config.{SvOnboardingConfig, TrafficControlConfig}
import com.daml.network.util.{Auth0Util, CommonCNNodeAppInstanceReferences}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.integration.*
import com.digitalasset.canton.protocol.LfContractId
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.{AppendedClues, BeforeAndAfterEach}

import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.language.implicitConversions
import scala.math.BigDecimal.RoundingMode
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** Analogue to Canton's CommunityTests */
object CNNodeTests {
  type CNNodeTestConsoleEnvironment = TestConsoleEnvironment[CNNodeEnvironmentImpl]
  type SharedCNNodeEnvironment =
    SharedEnvironment[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment]
  type IsolatedCNNodeEnvironments =
    IsolatedEnvironments[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment]

  trait CNNodeIntegrationTest
      extends BaseIntegrationTest[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment]
      with IsolatedCNNodeEnvironments
      with CNNodeTestCommon
      with LedgerApiExtensions {

    protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq.empty

    registerPlugin(new WaitForPorts(extraPortsToWaitFor))

    override def environmentDefinition
        : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
      CNNodeEnvironmentDefinition
        .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  trait CNNodeIntegrationTestWithSharedEnvironment
      extends BaseIntegrationTest[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment]
      with SharedCNNodeEnvironment
      with BeforeAndAfterEach
      with CNNodeTestCommon
      with LedgerApiExtensions {

    protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq.empty

    registerPlugin(new WaitForPorts(extraPortsToWaitFor))

    override def environmentDefinition
        : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
      CNNodeEnvironmentDefinition
        .simpleTopology1Sv(this.getClass.getSimpleName)

    // We append this to configured Daml user names for isolation across test cases.
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    @volatile
    private var testCaseId: Int = 0

    override def beforeEach(): Unit = {
      logger.info(s"Starting test case $testCaseId")
      super.beforeEach()
    }

    override def testFinished(env: CNNodeTestConsoleEnvironment): Unit = {
      testCaseId += 1
      super.testFinished(env)
    }

    // make `aliceWallet` etc. use updated usernames
    override def uwc(name: String)(implicit
        env: CNNodeTestConsoleEnvironment
    ): WalletAppClientReference = extendLedgerApiUserWithCaseId(super.wc(name))

    override def uamc(name: String)(implicit
        env: CNNodeTestConsoleEnvironment
    ): AppManagerAppClientReference = extendLedgerApiUserWithCaseId(super.uamc(name))

    // make `aliceCns` etc. use updated usernames
    override def rdpe(name: String)(implicit
        env: CNNodeTestConsoleEnvironment
    ): CnsExternalAppClientReference =
      extendLedgerApiUserWithCaseId(super.rdpe(name))

    // make `aliceSplitwell` etc. use updated usernames
    override def rsw(name: String)(implicit
        env: CNNodeTestConsoleEnvironment
    ): SplitwellAppClientReference = extendLedgerApiUserWithCaseId(super.rsw(name))(env.actorSystem)

    override def perTestCaseName(name: String) = s"${name}_tc$testCaseId.unverified.cns"
    def perTestCaseNameWithoutUnverified(name: String) = s"${name}_tc$testCaseId"

    private def extendLedgerApiUserWithCaseId(
        ref: WalletAppClientReference
    ): WalletAppClientReference = {
      val newLedgerApiUser = perTestCaseNameWithoutUnverified(ref.config.ledgerApiUser)
      new WalletAppClientReference(
        ref.cnNodeConsoleEnvironment,
        ref.name,
        config = ref.config.copy(ledgerApiUser = newLedgerApiUser),
      )
    }

    private def extendLedgerApiUserWithCaseId(
        ref: AppManagerAppClientReference
    ): AppManagerAppClientReference = {
      val newLedgerApiUser = perTestCaseNameWithoutUnverified(ref.config.ledgerApiUser)
      new AppManagerAppClientReference(
        ref.cnNodeConsoleEnvironment,
        ref.name,
        config = ref.config.copy(ledgerApiUser = newLedgerApiUser),
      )
    }

    private def extendLedgerApiUserWithCaseId(
        ref: CnsExternalAppClientReference
    ): CnsExternalAppClientReference = {
      val newLedgerApiUser = perTestCaseNameWithoutUnverified(ref.config.ledgerApiUser)
      new CnsExternalAppClientReference(
        ref.cnNodeConsoleEnvironment,
        ref.name,
        config = ref.config.copy(ledgerApiUser = newLedgerApiUser),
      )
    }

    private def extendLedgerApiUserWithCaseId(
        ref: SplitwellAppClientReference
    )(implicit actorSystem: ActorSystem): SplitwellAppClientReference = {
      val newLedgerApiUser = perTestCaseNameWithoutUnverified(ref.config.ledgerApiUser)
      val newLedgerApiConfig = ref.config.participantClient.ledgerApi
        .copy(authConfig =
          updateUser(ref.config.participantClient.ledgerApi.authConfig, newLedgerApiUser)
        )
      val newRemoteParticipant = ref.config.participantClient.copy(ledgerApi = newLedgerApiConfig)
      new SplitwellAppClientReference(
        ref.cnNodeConsoleEnvironment,
        ref.name,
        config = ref.config
          .copy(ledgerApiUser = newLedgerApiUser, participantClient = newRemoteParticipant),
      )
    }

    private def updateUser(
        conf: AuthTokenSourceConfig,
        newUser: String,
    ): AuthTokenSourceConfig = {
      conf match {
        case AuthTokenSourceConfig.Static(_, adminToken) => {
          val secret = "test" // used for all of our tests
          val userToken = AuthUtil.LedgerApi.testToken(newUser, secret)
          AuthTokenSourceConfig.Static(userToken, adminToken)
        }
        case AuthTokenSourceConfig.SelfSigned(audience, _, secret, adminToken) => {
          AuthTokenSourceConfig.SelfSigned(audience, newUser, secret, adminToken)
        }
        case _ => conf
      }
    }
  }

  trait CNNodeTestCommon
      extends BaseTest
      with CommonCNNodeAppInstanceReferences
      with LedgerApiExtensions
      with AppendedClues {

    protected def initSvc()(implicit env: CNNodeTestConsoleEnvironment): Unit = {
      env.fullSvcApps.local.foreach(_.start())
      env.fullSvcApps.local.foreach(_.waitForInitialization())
    }

    protected def initSvcWithSv1Only()(implicit env: CNNodeTestConsoleEnvironment): Unit = {
      env.minimalSvcApps.local.foreach(_.start())
      env.minimalSvcApps.local.foreach(_.waitForInitialization())
    }

    def defaultTickDuration(implicit env: CNNodeTestConsoleEnvironment): NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds((sv1Backend.config.onboarding match {
        case Some(foundCollective: SvOnboardingConfig.FoundCollective) =>
          foundCollective.initialTickDuration.asJava
        case Some(_: SvOnboardingConfig.JoinWithKey) | None =>
          fail("Failed to retrieve defaultTickDuration from sv1.")
      }).toSeconds)

    def tickDurationWithBuffer(implicit env: CNNodeTestConsoleEnvironment) =
      defaultTickDuration.asJava.plus(java.time.Duration.ofSeconds(10))

    def defaultTrafficControlConfig(implicit
        env: CNNodeTestConsoleEnvironment
    ): TrafficControlConfig =
      sv1Backend.config.onboarding match {
        case Some(foundCollective: SvOnboardingConfig.FoundCollective) =>
          foundCollective.initialTrafficControlConfig
        case Some(_: SvOnboardingConfig.JoinWithKey) | None =>
          fail("Failed to retrieve defaultTrafficControlConfig from sv1.")
      }

    def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal)): Unit = {
      value should (be >= range._1 and be <= range._2)
    }

    // Upper bound for fees in any of the above transfers
    val smallAmount: BigDecimal = BigDecimal(1.0)
    def beWithin(lower: BigDecimal, upper: BigDecimal): Matcher[BigDecimal] =
      be >= lower and be <= upper

    /** Asserts two BigDecimals are equal up to `n` decimal digits. */
    def beEqualUpTo(right: BigDecimal, n: Int): Matcher[BigDecimal] =
      Matcher { (left: BigDecimal) =>
        MatchResult(
          left.setScale(n, RoundingMode.HALF_EVEN) == right.setScale(n, RoundingMode.HALF_EVEN),
          s"$left was not equal to $right up to $n digits",
          s"$left was equal to $right up to $n digits",
        )
      }

    /** A function abstracting the common pattern of acting and then waiting for the action to
      * eventually have its expected results.
      */
    def actAndCheck[T, U](
        action: String,
        actionExpr: => T,
    )(check: String, checkFun: T => U): (T, U) = actAndCheck()(action, actionExpr)(check, checkFun)

    /** A function abstracting the common pattern of acting and then waiting for the action to
      * eventually have its expected results.
      */
    def actAndCheck[T, U](
        timeUntilSuccess: FiniteDuration = 20.seconds,
        maxPollInterval: FiniteDuration = 5.seconds,
    )(
        action: String,
        actionExpr: => T,
    )(check: String, checkFun: T => U): (T, U) = {
      {
        val x = clue(s"(act) $action")(actionExpr)
        withClue(s"Check $check for $action") {
          clue(s"(check) $check") {
            eventually(timeUntilSuccess, maxPollInterval) {
              val y = checkFun(x)
              (x, y)
            }
          }
        }
      }
    }

    /** A version of clue that does not emit logger.error message on TestFailedException.
      * Intended to be used when the clue is called inside an outer eventually() loop, in which case
      * we do not want to print errors on failures that will be retried by that external loop.
      */
    def silentClue[T](message: String)(expr: => T): T = {
      logger.debug(s"Running clue: ${message}")
      Try(expr) match {
        case Success(value) =>
          logger.debug(s"Finished clue: ${message}")
          value
        case Failure(ex) =>
          ex match {
            case _: TestFailedException =>
              logger.debug(s"Failed clue: ${message}", ex)
            case _ =>
              logger.error(s"Failed clue: ${message}", ex)
          }
          throw ex
      }
    }

    /** A version of actAndCheck that does not emit logger.error messages on TestFailedException.
      * Intended to be used when this is called inside an outer eventually() loop, in which case
      * we do not want to print errors on failures that will be retried by that external loop.
      */
    def silentActAndCheck[T, U](
        action: String,
        actionExpr: => T,
    )(check: String, checkFun: T => U): (T, U) = {
      {
        val x = silentClue(s"(act) $action")(actionExpr)
        silentClue(s"(check) $check") {
          eventually() {
            val y = checkFun(x)
            (x, y)
          }
        }
      }
    }

    /** Keeps evaluating `testCode` until it succeeds or a timeout occurs.
      */
    def eventuallySucceeds[T](
        timeUntilSuccess: FiniteDuration = 20.seconds,
        maxPollInterval: FiniteDuration = 5.seconds,
    )(testCode: => T): T = {
      eventually(timeUntilSuccess, maxPollInterval) {
        try {
          loggerFactory.suppressErrors(testCode)
        } catch {
          case NonFatal(e) => fail(e)
        }
      }
    }

    /** Changes `name` so it is unlikely to conflict with names used somewhere else.
      * Does nothing for isolated test environments, overloaded for shared environment.
      */
    def perTestCaseName(name: String) = name

    private def readMandatoryEnvVar(name: String): String = {
      sys.env.get(name) match {
        case None => fail(s"Environment variable $name must be set")
        case Some(s) if s.isEmpty => fail(s"Environment variable $name must be non-empty")
        case Some(s) => s
      }
    }

    def auth0UtilFromEnvVars(domain: String, tenantO: Option[String] = None): Auth0Util = {
      val tenant = tenantO.getOrElse(System.getProperty("AUTH0_TENANT"))
      val prefix = tenant match {
        // Used for preflight checks
        case "dev" => "AUTH0_CN"
        // Used for sv preflight checks
        case "sv" => "AUTH0_SV"
        // Used for validator preflight checks
        case "validator" => "AUTH0_VALIDATOR"
        // Used locally
        case "test" | "" | null => "AUTH0_TESTS"
        case _ => fail(s"Invalid value for AUTH0_TENANT property: $tenant")
      }
      val clientId = readMandatoryEnvVar(s"${prefix}_MANAGEMENT_API_CLIENT_ID");
      val clientSecret = readMandatoryEnvVar(s"${prefix}_MANAGEMENT_API_CLIENT_SECRET");

      retryAuth0Calls(new Auth0Util(domain, clientId, clientSecret, loggerFactory))
    }

    def retryAuth0Calls[T](f: => T): T = {
      eventually() {
        try {
          f
        } catch {
          case auth0Exception: Auth0Exception => {
            logger.debug("Auth0 exception raised, triggering retry...")
            fail(auth0Exception)
          }
          case ex: Throwable => throw ex // throw anything else
        }
      }
    }

    implicit def javaToScalaContractId[T](cid: ContractId[T]): LfContractId =
      LfContractId.assertFromString(cid.contractId)

    protected def startAllSync(nodes: CNNodeAppBackendReference*): Unit = {
      nodes.foreach(_.start())
      nodes.foreach(_.waitForInitialization())
    }

    def registerHttpConnectionPoolsCleanup(implicit
        env: TestEnvironment[CNNodeEnvironmentImpl]
    ): Unit = {
      implicit val sys = env.actorSystem
      implicit val ec = env.executionContext
      CoordinatedShutdown(sys).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "cleanup") {
        () =>
          Http().shutdownAllConnectionPools().map(_ => Done)
      }
    }
  }

  object BracketSynchronous {

    /** Start a synchronous ad-hoc bracket that puts the cleanup immediately
      * after the creation.  Sort of like try/finally but written backwards.
      *
      * {{{
      *  bracket(doSetup(), doCleanupFromSetup()) {
      *    doOtherThings
      *  }
      * }}}
      */
    @nowarn("cat=unused-params")
    def bracket[T](acquire: Any, release: => Any)(body: => T): T =
      try body
      finally release
  }
}
