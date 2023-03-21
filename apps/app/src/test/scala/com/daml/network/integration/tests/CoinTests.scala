package com.daml.network.integration.tests

import com.digitalasset.canton.protocol.LfContractId
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.auth0.exception.Auth0Exception
import com.daml.network.auth.AuthUtil
import com.daml.network.config.AuthTokenSourceConfig
import com.daml.network.console.{
  LedgerApiExtensions,
  RemoteDirectoryAppReference,
  SplitwellAppClientReference,
  WalletAppClientReference,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.{Auth0Util, CommonCoinAppInstanceReferences}
import com.daml.network.util.CoinUtil.defaultCoinConfig
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.integration.*
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import org.scalatest.BeforeAndAfterEach

import scala.annotation.nowarn
import scala.language.implicitConversions
import java.time.Duration
import scala.concurrent.duration.*
import scala.util.control.NonFatal
import com.daml.network.util.CoinUtil
import com.daml.network.integration.plugins.WaitForPorts
import org.scalatest.matchers.Matcher

/** Analogue to Canton's CommunityTests */
object CoinTests {
  type CoinTestConsoleEnvironment = TestConsoleEnvironment[CoinEnvironmentImpl]
  type SharedCoinEnvironment =
    SharedEnvironment[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
  type IsolatedCoinEnvironments =
    IsolatedEnvironments[CoinEnvironmentImpl, CoinTestConsoleEnvironment]

  trait CoinIntegrationTest
      extends BaseIntegrationTest[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
      with IsolatedCoinEnvironments
      with CoinTestCommon
      with LedgerApiExtensions {

    protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq.empty

    registerPlugin(new WaitForPorts(extraPortsToWaitFor))

    override def environmentDefinition
        : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
      CoinEnvironmentDefinition
        .simpleTopology(this.getClass.getSimpleName)

    protected def initSvc()(implicit env: CoinTestConsoleEnvironment): Unit = {
      env.appsHostedBySvc.local.foreach(_.start())
      env.appsHostedBySvc.local.foreach(_.waitForInitialization())
    }

  }

  trait CoinIntegrationTestWithSharedEnvironment
      extends BaseIntegrationTest[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
      with SharedCoinEnvironment
      with BeforeAndAfterEach
      with CoinTestCommon
      with LedgerApiExtensions {

    protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq.empty

    registerPlugin(new WaitForPorts(extraPortsToWaitFor))

    override def environmentDefinition
        : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
      CoinEnvironmentDefinition
        .simpleTopology(this.getClass.getSimpleName)

    // We append this to configured Daml user names for isolation across test cases.
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    @volatile
    @nowarn("cat=unused-params")
    private var testCaseId: Int = 0

    override def beforeEach(): Unit = {
      logger.info(s"Starting test case $testCaseId")
      super.beforeEach()
    }

    override def testFinished(env: CoinTestConsoleEnvironment): Unit = {
      testCaseId += 1
      super.testFinished(env)
    }

    // make `aliceWallet` etc. use updated usernames
    override def uwc(name: String)(implicit
        env: CoinTestConsoleEnvironment
    ): WalletAppClientReference = extendLedgerApiUserWithCaseId(super.wc(name))

    // make `aliceDirectory` etc. use updated usernames
    override def rdp(name: String)(implicit
        env: CoinTestConsoleEnvironment
    ): RemoteDirectoryAppReference = extendLedgerApiUserWithCaseId(super.rdp(name))

    // make `aliceSplitwell` etc. use updated usernames
    override def rsw(name: String)(implicit
        env: CoinTestConsoleEnvironment
    ): SplitwellAppClientReference = extendLedgerApiUserWithCaseId(super.rsw(name))

    override def perTestCaseName(name: String) = s"${name}_tc$testCaseId"

    private def extendLedgerApiUserWithCaseId(
        ref: WalletAppClientReference
    ): WalletAppClientReference = {
      val newLedgerApiUser = perTestCaseName(ref.config.ledgerApiUser)
      new WalletAppClientReference(
        ref.coinConsoleEnvironment,
        ref.name,
        config = ref.config.copy(ledgerApiUser = newLedgerApiUser),
      )
    }

    private def extendLedgerApiUserWithCaseId(
        ref: RemoteDirectoryAppReference
    ): RemoteDirectoryAppReference = {
      val newLedgerApiUser = perTestCaseName(ref.config.ledgerApiUser)
      val newLedgerApiConfig = ref.config.ledgerApi
        .copy(authConfig = updateUser(ref.config.ledgerApi.authConfig, newLedgerApiUser))
      new RemoteDirectoryAppReference(
        ref.coinConsoleEnvironment,
        ref.name,
        config = ref.config.copy(ledgerApiUser = newLedgerApiUser, ledgerApi = newLedgerApiConfig),
      )
    }

    private def extendLedgerApiUserWithCaseId(
        ref: SplitwellAppClientReference
    ): SplitwellAppClientReference = {
      val newLedgerApiUser = perTestCaseName(ref.config.ledgerApiUser)
      val newLedgerApiConfig = ref.config.remoteParticipant.ledgerApi
        .copy(authConfig =
          updateUser(ref.config.remoteParticipant.ledgerApi.authConfig, newLedgerApiUser)
        )
      val newRemoteParticipant = ref.config.remoteParticipant.copy(ledgerApi = newLedgerApiConfig)
      new SplitwellAppClientReference(
        ref.coinConsoleEnvironment,
        ref.name,
        config = ref.config
          .copy(ledgerApiUser = newLedgerApiUser, remoteParticipant = newRemoteParticipant),
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

  trait CoinTestCommon
      extends BaseTest
      with CommonCoinAppInstanceReferences
      with LedgerApiExtensions {

    lazy val defaultTickDuration = NonNegativeFiniteDuration(Duration.ofSeconds(150))

    protected def mkCoinConfig(
        tickDuration: NonNegativeFiniteDuration = defaultTickDuration,
        maxNumInputs: Int = 100,
        holdingFee: BigDecimal = CoinUtil.defaultHoldingFee.rate,
    ): cc.coinconfig.CoinConfig[cc.coinconfig.USD] =
      defaultCoinConfig(
        tickDuration,
        maxNumInputs,
        holdingFee,
      )

    def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal)): Unit = {
      value should (be >= range._1 and be <= range._2)
    }

    // Upper bound for fees in any of the above transfers
    val smallAmount: BigDecimal = BigDecimal(1.0)
    def beWithin(lower: BigDecimal, upper: BigDecimal): Matcher[BigDecimal] =
      be >= lower and be <= upper

    /** A function abstracting the common pattern of acting and then waiting for the action to
      * eventually have its expected results.
      */
    def actAndCheck[T, U](
        action: String,
        actionExpr: => T,
    )(check: String, checkFun: T => U): (T, U) = {
      {
        val x = clue(s"(act) $action")(actionExpr)
        clue(s"(check) $check") {
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

    def auth0UtilFromEnvVars(domain: String): Auth0Util = {
      val tenant = System.getProperty("AUTH0_TENANT")
      val prefix = tenant match {
        // Used for preflight checks
        case "dev" => "AUTH0"
        // Used locally
        case "test" | "" | null => "AUTH0_TESTS"
        case _ => fail(s"Invalid value for AUTH0_TENANT property: $tenant")
      }
      val clientId = readMandatoryEnvVar(s"${prefix}_MANAGEMENT_API_CLIENT_ID");
      val clientSecret = readMandatoryEnvVar(s"${prefix}_MANAGEMENT_API_CLIENT_SECRET");

      retryAuth0Calls(new Auth0Util(domain, clientId, clientSecret))
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
