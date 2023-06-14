package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.util.{FrontendLoginUtil, DirectoryFrontendTestUtil, WalletTestUtil}

import org.openqa.selenium.support.ui.ExpectedConditions

class XNodeDirectoryFrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with DirectoryFrontendTestUtil
    with FrontendLoginUtil {

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  override def environmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopologyXCentralizedDomain(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .withAdditionalSetup(implicit env => {
        aliceValidator.participantClient.upload_dar_unless_exists(directoryDarPath)
      })

  "A directory UI" should {

    "allow requesting an entry with subscription payments and then list it" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(100.0)

      val entryName = "mycool+entry"

      aliceWallet.listSubscriptionRequests() shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        // login to wallet UI once to create saved localstorage auth session
        login(3000, aliceDamlUser)

        reserveDirectoryNameFor(
          () => login(3004, aliceDamlUser),
          entryName,
          "1.0",
          "USD",
          "90 days",
        )

        clue("requesting an existing name to check the already taken message") {
          waitForQuery(id("entry-name-field"))
          click on "entry-name-field"
          textField("entry-name-field").value = entryName

          waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
          click on "search-entry-button"
          waitForQuery(id("unavailable-icon"))
        }
      }
    }

    "allow login via auth0" taggedAs LocalAuth0Test in { implicit env =>
      withAuth0LoginCheck("alice", 3004)((_, _, _) => ())
    }
  }
}
