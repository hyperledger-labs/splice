package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, AnsFrontendTestUtil, WalletTestUtil}

import org.openqa.selenium.support.ui.ExpectedConditions

class AnsFrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with AnsFrontendTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)

  "A Name Service UI" should {

    "allow requesting an entry with subscription payments and then list it" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      val entryName = s"mycool_entry.unverified.$ansAcronym"
      val entryNameWithoutSufffix = "mycool_entry"

      aliceWalletClient.listSubscriptionRequests() shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        // login to wallet UI once to create saved localstorage auth session
        login(aliceWalletUIPort, aliceDamlUser)

        reserveAnsNameFor(
          () => login(aliceAnsUIPort, aliceDamlUser),
          entryName,
          "1.0000000000",
          "USD",
          "90 days",
          ansAcronym,
        )

        clue("requesting an existing name to check the already taken message") {
          waitForQuery(id("entry-name-field"))
          eventuallyClickOn(id("entry-name-field"))
          textField("entry-name-field").value = entryNameWithoutSufffix

          waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
          eventuallyClickOn(id("search-entry-button"))
          waitForQuery(id("unavailable-icon"))
        }
      }
    }

    "allow requesting length of an entry which just reaches the limit" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      val suffix = s".unverified.$ansAcronym"
      val entryNameJustReachesLimit = "a" * (60 - suffix.length) + suffix

      aliceWalletClient.listSubscriptionRequests() shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        // login to wallet UI once to create saved localstorage auth session
        login(aliceWalletUIPort, aliceDamlUser)

        reserveAnsNameFor(
          () => login(aliceAnsUIPort, aliceDamlUser),
          entryNameJustReachesLimit,
          "1.0000000000",
          "USD",
          "90 days",
          ansAcronym,
        )
      }
    }

    "reject the request of an invalid name" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      val entryName = "bad.entry.name"

      withFrontEnd("alice") { implicit webDriver =>
        // login to wallet UI once to create saved localstorage auth session
        login(aliceWalletUIPort, aliceDamlUser)
        login(aliceAnsUIPort, aliceDamlUser)
        waitForQuery(id("entry-name-field"))

        clue("requesting an invalid name to check invalid name message") {
          waitForQuery(id("entry-name-field"))
          eventuallyClickOn(id("entry-name-field"))
          textField("entry-name-field").value = entryName;

          waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
          eventuallyClickOn(id("search-entry-button"))

          waitForQuery(id("unavailable-icon"))
          find(id("entry-name-validation-message")).fold(fail("Unable to find validation message"))(
            _.text should startWith("The provided entry name has an invalid format")
          )
        }
      }
    }

    "reject the request if the length of the name is over the limit" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      val entryNameJustOverLimit = "a" * (61 - s".unverified.$ansAcronym".length)

      withFrontEnd("alice") { implicit webDriver =>
        // login to wallet UI once to create saved localstorage auth session
        login(aliceWalletUIPort, aliceDamlUser)
        login(aliceAnsUIPort, aliceDamlUser)
        waitForQuery(id("entry-name-field"))

        clue("requesting an name of length over limit to check invalid name message") {
          waitForQuery(id("entry-name-field"))
          eventuallyClickOn(id("entry-name-field"))
          textField("entry-name-field").value = entryNameJustOverLimit;

          waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
          eventuallyClickOn(id("search-entry-button"))

          waitForQuery(id("unavailable-icon"))
          find(id("entry-name-validation-message")).fold(fail("Unable to find validation message"))(
            _.text should startWith(
              "The provided entry name has an invalid format. Maximum 60 characters(including suffix), a-z, 0-9, - and _ are supported."
            )
          )
        }
      }
    }
  }
}
