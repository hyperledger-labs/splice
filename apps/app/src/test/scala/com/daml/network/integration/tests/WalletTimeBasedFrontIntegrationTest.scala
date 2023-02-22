package com.daml.network.integration.tests

import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.{
  FrontendLoginUtil,
  TimeTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}

import java.time.Duration

class WalletTimeBasedFrontIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TimeTestUtil {

  override def environmentDefinition: CoinEnvironmentDefinition =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  "A wallet UI" should {

    "show balance and locked coins" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWallet, aliceValidator)
      val aliceValidatorParty = aliceValidator.getValidatorPartyId()
      val tapQty = 50
      val lockedQty = 10

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        eventually() {
          find(id("locked-qty")).value.text should matchText("0.0000000000")
          find(id("unlocked-qty")).value.text should matchText("0.0000000000")
        }

        tapAndListCoins(tapQty)

        lockCoins(
          aliceWalletBackend,
          aliceParty,
          aliceValidatorParty,
          aliceWallet.list().coins,
          lockedQty,
          scan,
          Duration.ofDays(1),
        )

        eventually() {
          val currentLockedQty = BigDecimal(find(id("locked-qty")).value.text)
          val currentUnlockedQty = BigDecimal(find(id("unlocked-qty")).value.text)
          val currentHoldingFees = BigDecimal(find(id("holding-fees")).value.text)
          val expectedUnlockedQty = tapQty - lockedQty

          assertInRange(currentUnlockedQty, (expectedUnlockedQty - 1, expectedUnlockedQty))
          assertInRange(currentLockedQty, (lockedQty, lockedQty))
          assertInRange(currentHoldingFees, (0, 1))
        }
      }
    }

  }
}
