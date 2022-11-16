package com.daml.network.integration.tests

import com.daml.network.integration.CoinEnvironmentDefinition

import scala.concurrent.duration.DurationInt

class DirectoryFrontendIntegrationTest extends FrontendIntegrationTest("alice") {

  private val directoryDarPath =
    "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"

  override def environmentDefinition =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "A directory UI" should {

    "allow requesting an entry and then list it" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)
      aliceRemoteWallet.tap(100.0)

      val entryName = "mycoolentry"

      withFrontEnd("alice") { implicit webDriver =>
        go to "http://localhost:3004"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        eventually(scaled(10 seconds)) {
          click on "entry-name-field"
        }
        textField("entry-name-field").value = entryName
        click on "request-entry-button"

        // Alice is redirected to wallet...
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on className("accept-button")
        // And then back to directory
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"

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

    "allow requesting an entry with subscription payments and then list it" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)
      aliceRemoteWallet.tap(100.0)

      val entryName = "mycoolentry"

      aliceRemoteWallet.listSubscriptionRequests() shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        go to "http://localhost:3004"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        eventually(scaled(10 seconds)) {
          click on "entry-name-field"
        }
        textField("entry-name-field").value = entryName
        click on "request-entry-with-sub-button"

        // Alice is redirected to wallet...
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on className("sub-request-accept-button")
        // And then back to directory
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"

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
}
