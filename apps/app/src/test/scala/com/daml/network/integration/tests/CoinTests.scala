package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.Auth0Util
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.*

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
      with CommonCoinAppInstanceReferences {

    override def environmentDefinition
        : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
      CoinEnvironmentDefinition
        .simpleTopology(this.getClass.getSimpleName)

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

    def auth0UtilFromSystemPoperties(domain: String): Auth0Util = {
      val clientId = System.getProperty("AUTH0_MANAGEMENT_API_CLIENT_ID");
      val clientSecret = System.getProperty("AUTH0_MANAGEMENT_API_CLIENT_SECRET");

      if (clientId == null || clientId.isEmpty()) {
        fail(
          "No clientId given, please supply auth0 clientId through system property AUTH0_MANAGEMENT_API_CLIENT_ID"
        )
      }
      if (clientSecret == null || clientSecret.isEmpty()) {
        fail(
          "No clientSecret given, please supply auth0 clientSecret through system property AUTH0_MANAGEMENT_API_CLIENT_SECRET"
        )
      }
      new Auth0Util(domain, clientId, clientSecret)
    }
  }
}
