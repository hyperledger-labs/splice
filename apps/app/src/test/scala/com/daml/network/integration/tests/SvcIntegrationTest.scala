package com.daml.network.integration.tests

import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.console.CommandFailure

class SvcIntegrationTest extends CoinIntegrationTest with WalletTestUtil {

  "restart cleanly" in { implicit env =>
    // TODO(tech-debt): share tests for common properties of CoinApps, like restartabilty
    svc.stop()
    svc.startSync()
  }

  "manage featured app rights" in { implicit env =>
    onboardWalletUser(splitwellProviderWallet, splitwellValidator)

    scan.listFeaturedAppRights() should be(empty)
    splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe false

    val splitwellProvider = providerSplitwellBackend.getProviderPartyId()
    actAndCheck(
      "grant a featured app right to splitwell provider", {
        svcClient.grantFeaturedAppRight(splitwellProvider)
      },
    )(
      "splitwell provider is featured",
      { _ =>
        inside(scan.listFeaturedAppRights()) { case Seq(r) =>
          r.payload.provider shouldBe splitwellProvider.toProtoPrimitive
        }
        splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe true
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
          r1.payload.provider should (be(splitwellProvider.toProtoPrimitive) or be(
            svcParty.toProtoPrimitive
          ))
          r2.payload.provider should (be(splitwellProvider.toProtoPrimitive) or be(
            svcParty.toProtoPrimitive
          ))
        }
      },
    )

    clue("Try re-granting a featured app right to a provider that already has it")(
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          svcClient.grantFeaturedAppRight(splitwellProvider),
          _.errorMessage should include("already has a featured app right"),
        )
      )
    )

    actAndCheck(
      "withdraw splitwell's featured app right", {
        svcClient.withdrawFeaturedAppRight(splitwellProvider)
      },
    )(
      "splitwell is no longer featured",
      { _ =>
        inside(scan.listFeaturedAppRights()) { case Seq(r) =>
          r.payload.provider should not be (splitwellProvider.toProtoPrimitive)
        }
      },
    )

    clue("Try withdrawing an already withdrawn right")(
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          svcClient.withdrawFeaturedAppRight(splitwellProvider),
          _.errorMessage should include("No featured app right found for provider"),
        )
      )
    )

  }

  "list connected domains of svc and sv app" in { implicit env =>
    eventually() {
      svc.listConnectedDomains().keySet shouldBe Set("global")
    }
    eventually() {
      sv1.listConnectedDomains().keySet shouldBe Set("global")
    }
  }
}
