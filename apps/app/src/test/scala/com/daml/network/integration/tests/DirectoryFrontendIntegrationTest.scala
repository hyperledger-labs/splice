package com.daml.network.integration.tests

import scala.concurrent.duration.DurationInt

class DirectoryFrontendIntegrationTest extends FrontendIntegrationTest {

  "A directory UI" should {

    "allow requesting an entry and then list it" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)
      aliceRemoteWallet.tap(100.0)

      val entryName = "mycoolentry"

      go to "http://localhost:3004"
      click on "user-id-field"
      textField("user-id-field").value = aliceDamlUser
      click on "login-button"
      eventually(scaled(10 seconds)) {
        click on "entry-name-field"
      }
      textField("entry-name-field").value = entryName
      click on "request-entry-button"
      eventually(scaled(5 seconds)) {
        aliceRemoteWallet.listAppPaymentRequests() should have size 1
      }
      inside(aliceRemoteWallet.listAppPaymentRequests()) { case Seq(paymentRequest) =>
        aliceRemoteWallet.acceptAppPaymentRequest(paymentRequest.contractId)
      }
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
}
