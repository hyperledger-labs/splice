package com.daml.network.integration.tests

import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.WalletTestUtil

// TODO(tech-debt): Add tests that cover all possible CoinEvents
class ScanIntegrationTest extends CoinIntegrationTest with WalletTestUtil {

  "restart cleanly" in { implicit env =>
    scan.stop()
    scan.startSync()
  }

  "list one connected domain" in { implicit env =>
    eventually() {
      scan.listConnectedDomains().keySet shouldBe Set("global")
    }
  }
}
