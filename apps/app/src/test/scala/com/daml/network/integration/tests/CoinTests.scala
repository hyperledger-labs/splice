package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.digitalasset.canton.integration.{
  BaseIntegrationTest,
  IsolatedEnvironments,
  SharedEnvironment,
  TestConsoleEnvironment,
}

/** Analogue to Canton's CommunityTests */
object CoinTests {
  type CoinTestConsoleEnvironment = TestConsoleEnvironment[CoinEnvironmentImpl]
  type CoinIntegrationTest =
    BaseIntegrationTest[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
  type SharedCoinEnvironment =
    SharedEnvironment[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
  type IsolatedCoinEnvironments =
    IsolatedEnvironments[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
}
