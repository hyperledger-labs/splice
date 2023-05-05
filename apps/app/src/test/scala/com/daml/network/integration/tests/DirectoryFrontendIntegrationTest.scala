package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.util.{FrontendLoginUtil, WalletTestUtil}

import java.time.format.DateTimeFormatter
import java.time.{Duration, LocalDateTime}
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
        aliceValidator.participantClient.dars.upload(directoryDarPath)
      })

  "A directory UI" should {

    "allow requesting an entry with subscription payments and then list it" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(100.0)

      val entryName = "mycoolentry"
      val expiry = LocalDateTime.now().plus(Duration.ofDays(90))
      val expectedExpiry =
        DateTimeFormatter
          .ofPattern("MM/dd/yyyy HH:mm")
          .format(expiry)

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
        val goToDirectoryEntriesButton = eventually() {
          find(id("directory-entries-button")).valueOrFail("The success page did not load.")
        }
        click on goToDirectoryEntriesButton

        eventually(scaled(10 seconds)) {
          val row: Element = inside(findAll(className("entries-table-row")).toList) {
            case Seq(row) =>
              row
          }
          val name = row.childElement(className("entries-table-name")).text
          val amount = row.childElement(className("entries-table-amount")).text
          val currency = row.childElement(className("entries-table-currency")).text
          val interval = row.childElement(className("entries-table-payment-interval")).text
          val expiresAt = row.childElement(className("entries-table-expires-at")).text

          name should be(entryName)
          amount should be("1.0")
          currency should be("USD")
          interval should be("90 days")

          // allowing for some variance on seconds to avoid a flaky test
          val expiryWithSecsOffset = expectedExpiry.substring(0, expectedExpiry.length - 1)
          expiresAt should startWith(expiryWithSecsOffset)
        }
      }
    }

    "allow login via auth0" taggedAs LocalAuth0Test in { implicit env =>
      withAuth0LoginCheck("alice", 3004)((_, _, _) => ())
    }
  }
}
