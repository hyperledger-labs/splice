package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.util.{FrontendLoginUtil, DirectoryFrontendTestUtil, WalletTestUtil}

import org.openqa.selenium.support.ui.ExpectedConditions

class DirectoryFrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with DirectoryFrontendTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  "A directory UI" should {

    "allow requesting an entry with subscription payments and then list it" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      val entryName = "mycool_entry.unverified.cns"
      val entryNameWithoutSufffix = "mycool_entry"

      aliceWalletClient.listSubscriptionRequests() shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        // login to wallet UI once to create saved localstorage auth session
        login(aliceWalletUIPort, aliceDamlUser)

        reserveDirectoryNameFor(
          () => login(aliceDirectoryUIPort, aliceDamlUser),
          entryName,
          "1.0000000000",
          "USD",
          "90 days",
        )

        clue("requesting an existing name to check the already taken message") {
          waitForQuery(id("entry-name-field"))
          click on "entry-name-field"
          textField("entry-name-field").value = entryNameWithoutSufffix

          waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
          click on "search-entry-button"
          waitForQuery(id("unavailable-icon"))
        }
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
        login(aliceDirectoryUIPort, aliceDamlUser)
        waitForQuery(id("entry-name-field"))

        clue("requesting an invalid name to check invalid name message") {
          waitForQuery(id("entry-name-field"))
          click on "entry-name-field"
          textField("entry-name-field").value = entryName

          waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
          click on "search-entry-button"
          waitForQuery(id("unavailable-icon"))
          find(id("entry-name-validation-message")).fold(fail("Unable to find validation message"))(
            _.text should startWith("The provided entry name has an invalid format")
          )
        }
      }
    }
  }
}
