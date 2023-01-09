package com.daml.network.integration.tests

import com.auth0.exception.Auth0Exception
import com.daml.network.console.WalletAppClientReference
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
    override def wc(name: String)(implicit
        env: CoinTestConsoleEnvironment
    ): WalletAppClientReference = extendDamlUserWithCaseId(super.wc(name))

    private def extendDamlUserWithCaseId(
        walletAppClient: WalletAppClientReference
    ): WalletAppClientReference = {
      val newDamlUser = s"${walletAppClient.config.damlUser}-$testCaseId"
      new WalletAppClientReference(
        walletAppClient.coinConsoleEnvironment,
        walletAppClient.name,
        config = walletAppClient.config.copy(damlUser = newDamlUser),
      )
    }
  }

  trait CoinTestCommon extends BaseTest with CnsTestUtil with CommonCoinAppInstanceReferences {

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
