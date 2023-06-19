package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{
  FrontendLoginUtil,
  TimeTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.openqa.selenium.StaleElementReferenceException

import java.time.Duration

class WalletFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TimeTestUtil {

  val coinPrice = 2
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXWithSimTime(this.getClass.getSimpleName)
      .withCoinPrice(coinPrice)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)

  "A wallet UI" should {

    "onboard a new user" in { implicit env =>
      // Note: the test generates a unique user for each test
      val newRandomUser = aliceWallet.config.ledgerApiUser

      withFrontEnd("alice") { implicit webDriver =>
        login(3000, newRandomUser)

        // After a short delay, the UI should realize that the user is not onboarded,
        // and switch to the onboarding page.
        click on "onboard-button"
        // The onboard button should immediately be disabled, to prevent further clicking.
        try {
          find(id("onboard-button")) match {
            case Some(e) => e.isEnabled shouldBe false
            case _ => // The page went back to the default view before we could check the button
          }
        } catch {
          case _: StaleElementReferenceException =>
          // The reference to the button became stale due to a page redraw that happened shortly after the `find`;
          // this can be because the page went back to the default view before we could check the button
        }

        userIsLoggedIn()
      }
    }

    "allow logging in & logging out" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidator)
      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        actAndCheck(
          "Alice logs out", {
            click on "logout-button"
          },
        )("Alice sees the login screen again", _ => find(id("login-button")) should not be empty)
      }
    }

    "show user details after login" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val entryName = perTestCaseName("alice.cns")

      createDirectoryEntry(
        aliceParty,
        aliceDirectory,
        entryName,
        aliceWallet,
      )

      eventuallySucceeds() {
        directory.lookupEntryByName(entryName)
      }

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        eventually() {
          val loggedInUser = find(id("logged-in-user")).value.text.trim
          loggedInUser shouldBe entryName
        }
      }
    }

    "show party id after login if user has no cns entry" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser

      val alicePartyId = onboardWalletUser(aliceWallet, aliceValidator)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        eventually() {
          val loggedInUser = find(id("logged-in-user")).value.text.trim
          loggedInUser shouldBe alicePartyId.toProtoPrimitive
        }
      }
    }

    "show balances after login" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWallet, aliceValidator)
      actAndCheck("alice taps", aliceWallet.tap(2))(
        "alice balance is bigger than 1",
        _ => aliceWallet.balance().unlockedQty should be > BigDecimal(1.5),
      )
      lockCoins(
        aliceValidator,
        aliceParty,
        aliceValidator.getValidatorPartyId(),
        aliceWallet.list().coins,
        BigDecimal(1),
        sv1Scan,
        Duration.ofDays(1),
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        eventually() {
          val ccText = find(id("wallet-balance-cc")).value.text.trim
          val usdText = find(id("wallet-balance-usd")).value.text.trim

          ccText should not be "..."
          usdText should not be "..."
          val cc = BigDecimal(ccText.split(" ").head)
          val usd = BigDecimal(usdText.split(" ").head)

          assertInRange(cc, (BigDecimal(1) - smallAmount, BigDecimal(1)))
          assertInRange(
            usd,
            ((BigDecimal(1) - smallAmount) * coinPrice, BigDecimal(1) * coinPrice),
          )
        }
      }
    }

  }
}
