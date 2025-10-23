package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  TimeTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import org.openqa.selenium.StaleElementReferenceException

import java.time.Duration

class WalletFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TimeTestUtil {

  val amuletPrice = 2
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)

  "A wallet UI" should {

    "onboard a new user" in { implicit env =>
      // Note: the test generates a unique user for each test
      val newRandomUser = aliceWalletClient.config.ledgerApiUser

      withFrontEnd("alice") { implicit webDriver =>
        login(3000, newRandomUser)

        // After a short delay, the UI should realize that the user is not onboarded,
        // and switch to the onboarding page.
        val onboardButton = eventually() {
          find(id("onboard-button")).valueOrFail("Onboard button not found")
        }
        click on onboardButton
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
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
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
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val entryName = perTestCaseName("alice")

      createAnsEntry(
        aliceAnsExternalClient,
        entryName,
        aliceWalletClient,
      )

      eventuallySucceeds() {
        sv1ScanBackend.lookupEntryByName(entryName)
      }

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        eventually() {
          val loggedInUser = seleniumText(find(id("logged-in-user")))
          loggedInUser shouldBe entryName
        }
      }
    }

    "show party id after login if user has no ans entry" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser

      val alicePartyId = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        eventually() {
          val loggedInUser = seleniumText(find(id("logged-in-user")))
          loggedInUser shouldBe alicePartyId.toProtoPrimitive
        }
      }
    }

    "show balances after login" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      actAndCheck("alice taps", aliceWalletClient.tap(2 * amuletPrice))(
        "alice balance is bigger than 1",
        _ => aliceWalletClient.balance().unlockedQty should be > BigDecimal(1.5),
      )
      lockAmulets(
        aliceValidatorBackend,
        aliceParty,
        aliceValidatorBackend.getValidatorPartyId(),
        aliceWalletClient.list().amulets,
        BigDecimal(1),
        sv1ScanBackend,
        Duration.ofDays(1),
        getLedgerTime,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

        eventually() {
          val ccText = find(id("wallet-balance-amulet")).value.text.trim
          val usdText = find(id("wallet-balance-usd")).value.text.trim

          ccText should not be "..."
          usdText should not be "..."
          val cc = BigDecimal(ccText.split(" ").head)
          val usd = BigDecimal(usdText.split(" ").head)

          assertInRange(cc, (BigDecimal(1) - smallAmount, BigDecimal(1)))
          assertInRange(
            usd,
            ((BigDecimal(1) - smallAmount) * amuletPrice, BigDecimal(1) * amuletPrice),
          )
        }
      }
    }

  }
}
