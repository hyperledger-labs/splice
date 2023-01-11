package com.daml.network.integration.tests

import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest

class SvcIntegrationTest extends CoinIntegrationTest {

  "restart cleanly" in { implicit env =>
    // TODO(tech-debt): share tests for common properties of CoinApps, like restartabilty
    svc.stop()
    svc.startSync()
  }

  // TODO(M3-46): this test will probably become redundant soon
  "we have four sv apps and they are online" in { implicit env =>
    sv1.getDebugInfo()
    sv2.getDebugInfo()
    sv3.getDebugInfo()
    sv4.getDebugInfo()
  }

  "manage featured app rights" in { implicit env =>
    scan.listFeaturedAppRights() should be(empty)
  }
}
