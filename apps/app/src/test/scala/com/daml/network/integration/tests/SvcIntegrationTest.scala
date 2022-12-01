package com.daml.network.integration.tests

import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest

class SvcIntegrationTest extends CoinIntegrationTest {

  "restart cleanly" in { implicit env =>
    // TODO(M1-92): share tests for common properties of CoinApps, like restartabilty
    svc.stop()
    svc.startSync()
  }
}
