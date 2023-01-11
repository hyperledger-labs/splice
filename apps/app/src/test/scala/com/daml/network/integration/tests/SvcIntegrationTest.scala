package com.daml.network.integration.tests

import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.digitalasset.canton.console.CommandFailure

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

    val provider = providerSplitwiseBackend.getProviderPartyId()
    actAndCheck(
      "grant a featured app right to splitwise provider", {
        svcClient.grantFeaturedAppRight(provider)
      },
    )(
      "splitwise provider is featured",
      { _ =>
        inside(scan.listFeaturedAppRights()) { case Seq(r) =>
          r.payload.provider shouldBe provider.toProtoPrimitive
        }
      },
    )

    actAndCheck(
      "grant a featured app right to svc itself", {
        svcClient.grantFeaturedAppRight(svcParty)
      },
    )(
      "svc is also featured",
      { _ =>
        inside(scan.listFeaturedAppRights()) { case Seq(r1, r2) =>
          r1.payload.provider should (be(provider.toProtoPrimitive) or be(
            svcParty.toProtoPrimitive
          ))
          r2.payload.provider should (be(provider.toProtoPrimitive) or be(
            svcParty.toProtoPrimitive
          ))
        }
      },
    )

    clue("Try re-granting a featured app right to a provider that already has it")(
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          svcClient.grantFeaturedAppRight(provider),
          _.errorMessage should include("already has featured app rights"),
        )
      )
    )
  }
}
