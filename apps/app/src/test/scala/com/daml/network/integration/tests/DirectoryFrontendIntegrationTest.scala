package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.util.{FrontendLoginUtil, WalletTestUtil}

import scala.concurrent.duration.DurationInt

class DirectoryFrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil
    with FrontendLoginUtil {

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  override def environmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "A directory UI" should {

    "allow requesting an entry with subscription payments and then list it" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(100.0)

      val entryName = "mycoolentry"

      aliceWallet.listSubscriptionRequests() shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        login(3004, aliceDamlUser)
        eventually(scaled(10 seconds)) {
          click on "entry-name-field"
        }
        textField("entry-name-field").value = entryName
        click on "request-entry-with-sub-button"

        // Alice is redirected to wallet...
        loginOnCurrentPage(3000, aliceDamlUser)
        click on className("sub-request-accept-button")

        // And then back to directory, where she is already logged in
        eventually(scaled(10 seconds)) {
          findAll(className("entries-table-row")) should have size 1
        }
        val row: Element = inside(findAll(className("entries-table-row")).toList) { case Seq(row) =>
          row
        }
        val name = row.childElement(className("entries-table-name"))
        name.text should be(entryName)
      }
    }

    "allow login via auth0" taggedAs LocalAuth0Test in { implicit env =>
      withAuth0LoginCheck("alice", 3004)((_, _, _) => ())
    }
  }
}
