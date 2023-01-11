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

    val splitwiseProvider = providerSplitwiseBackend.getProviderPartyId()
    actAndCheck(
      "grant a featured app right to splitwise provider", {
        svcClient.grantFeaturedAppRight(splitwiseProvider)
      },
    )(
      "splitwise provider is featured",
      { _ =>
        inside(scan.listFeaturedAppRights()) { case Seq(r) =>
          r.payload.provider shouldBe splitwiseProvider.toProtoPrimitive
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
          r1.payload.provider should (be(splitwiseProvider.toProtoPrimitive) or be(
            svcParty.toProtoPrimitive
          ))
          r2.payload.provider should (be(splitwiseProvider.toProtoPrimitive) or be(
            svcParty.toProtoPrimitive
          ))
        }
      },
    )

    clue("Try re-granting a featured app right to a provider that already has it")(
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          svcClient.grantFeaturedAppRight(splitwiseProvider),
          _.errorMessage should include("already has a featured app right"),
        )
      )
    )

    actAndCheck(
      "withdraw splitwise's featured app right", {
        svcClient.withdrawFeaturedAppRight(splitwiseProvider)
      },
    )(
      "splitwise is no longer featured",
      { _ =>
        inside(scan.listFeaturedAppRights()) { case Seq(r) =>
          r.payload.provider should not be (splitwiseProvider.toProtoPrimitive)
        }
      },
    )

    clue("Try withdrawing an already withdrawn right")(
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          svcClient.withdrawFeaturedAppRight(splitwiseProvider),
          _.errorMessage should include("No featured app right found for provider"),
        )
      )
    )

  }
}
