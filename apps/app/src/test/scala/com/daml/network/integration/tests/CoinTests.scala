package com.daml.network.integration.tests

import com.auth0.exception.Auth0Exception
import com.daml.network.auth.AuthUtil
import com.daml.network.config.AuthTokenSourceConfig
import com.daml.network.console.{
  RemoteDirectoryAppReference,
  SplitwiseAppClientReference,
  WalletAppClientReference,
}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.{Auth0Util, CommonCoinAppInstanceReferences}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.integration.*
import org.scalatest.BeforeAndAfterEach

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
      with CoinTestCommon {

    override def environmentDefinition
        : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
      CoinEnvironmentDefinition
        .simpleTopology(this.getClass.getSimpleName)
  }

  trait CoinIntegrationTestWithSharedEnvironment
      extends BaseIntegrationTest[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
      with SharedCoinEnvironment
      with BeforeAndAfterEach
      with CoinTestCommon {

    override def environmentDefinition
        : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
      CoinEnvironmentDefinition
        .simpleTopology(this.getClass.getSimpleName)

    // We append this to configured Daml user names for isolation across test cases.
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    @volatile
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
    ): WalletAppClientReference = extendDamlUserWithCaseId(super.wc(name))

    // make `aliceDirectory` etc. use updated usernames
    override def rdp(name: String)(implicit
        env: CoinTestConsoleEnvironment
    ): RemoteDirectoryAppReference = extendDamlUserWithCaseId(super.rdp(name))

    // make `aliceSplitwise` etc. use updated usernames
    override def rsw(name: String)(implicit
        env: CoinTestConsoleEnvironment
    ): SplitwiseAppClientReference = extendDamlUserWithCaseId(super.rsw(name))

    override def perTestCaseName(name: String) = s"${name}_tc$testCaseId"

    private def extendDamlUserWithCaseId(
        ref: WalletAppClientReference
    ): WalletAppClientReference = {
      val newDamlUser = perTestCaseName(ref.config.damlUser)
      new WalletAppClientReference(
        ref.coinConsoleEnvironment,
        ref.name,
        config = ref.config.copy(damlUser = newDamlUser),
      )
    }
    private def extendDamlUserWithCaseId(
        ref: RemoteDirectoryAppReference
    ): RemoteDirectoryAppReference = {
      val newDamlUser = perTestCaseName(ref.config.damlUser)
      val newLedgerApiConfig = ref.config.ledgerApi
        .copy(authConfig = updateUser(ref.config.ledgerApi.authConfig, newDamlUser))
      new RemoteDirectoryAppReference(
        ref.coinConsoleEnvironment,
        ref.name,
        config = ref.config.copy(damlUser = newDamlUser, ledgerApi = newLedgerApiConfig),
      )
    }
    private def extendDamlUserWithCaseId(
        ref: SplitwiseAppClientReference
    ): SplitwiseAppClientReference = {
      val newDamlUser = perTestCaseName(ref.config.damlUser)
      val newLedgerApiConfig = ref.config.ledgerApi
        .copy(authConfig = updateUser(ref.config.ledgerApi.authConfig, newDamlUser))
      new SplitwiseAppClientReference(
        ref.coinConsoleEnvironment,
        ref.name,
        config = ref.config.copy(damlUser = newDamlUser, ledgerApi = newLedgerApiConfig),
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

  trait CoinTestCommon extends BaseTest with CommonCoinAppInstanceReferences {

    def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal)): Unit = {
      value should (be >= range._1 and be <= range._2)
    }

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
  }
}
