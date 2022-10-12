package com.daml.network.integration.tests

import com.daml.network.codegen.CN.{Directory => dirCodegen}
import com.daml.network.console.{LocalValidatorAppReference, RemoteDirectoryAppReference}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration.DurationInt
import scala.util.Try

class WalletFrontendIntegrationTest extends FrontendIntegrationTest {

  private val directoryDarPath =
    "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"

  "A wallet UI" should {

    "allow tapping coins and then list the created coins" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)

      go to "http://localhost:3000"
      click on "user-id-field"
      textField("user-id-field").value = aliceDamlUser
      click on "login-button"
      click on "tap-amount-field"
      textField("tap-amount-field").value = "15.0"
      click on "tap-button"
      eventually(scaled(5 seconds)) {
        findAll(className("coins-table-row")) should have size 1
      }
      val row = inside(findAll(className("coins-table-row")).toList) { case Seq(row) =>
        row
      }
      val quantity = row.childElement(className("coins-table-quantity"))
      quantity.text should be("15.0000000000")
    }

    "report errors" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)

      go to "http://localhost:3000"
      click on "user-id-field"
      textField("user-id-field").value = aliceDamlUser
      click on "login-button"
      click on "tap-amount-field"
      textField("tap-amount-field").value = "non-numeric"
      loggerFactory.suppressErrors(click on "tap-button")
      eventually()(
        findAll(id("error")).toList should not be empty
      )
      consumeError("RpcError: Could not read Numeric string \"non-numeric\"")
    }

    "show logged in user details" in { implicit env =>
      // Create directory entry for alice
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val entryName = "alice.cns"
      val aliceParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      submitDirectoryEntryRequest(aliceParty, aliceDirectory, entryName)

      def getPaymentRequest() = aliceRemoteWallet.listAppPaymentRequests().headOption

      aliceRemoteWallet.tap(5.0)
      val walletPaymentRequest = eventually()(
        getPaymentRequest().getOrElse(fail("Payment request is unexpectedly not defined"))
      )
      val _ = aliceRemoteWallet.acceptAppPaymentRequest(walletPaymentRequest.contractId)

      def tryGetEntry() =
        Try(loggerFactory.suppressErrors(directory.lookupEntryByName(entryName)))

      eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $entryName")))

      // Browse to alice's wallet
      go to "http://localhost:3000"
      click on "user-id-field"
      textField("user-id-field").value = aliceDamlUser
      click on "login-button"
      eventually() {
        find(id("logged-in-user")).getOrElse(fail("Logged-in user information never showed up"))
      }

      // Check that alice is shown as the user, and her party ID has been resolved to its directory entry correctly.
      // We do this in another eventually() as a "..." text might appear momentarily, until the directory service responds.
      eventually() {
        val expected = s"alice.cns (${aliceParty.toProtoPrimitive})"
        find(id("logged-in-user")).value.text shouldBe expected
      }
    }

    "show app payment requests, and correctly handle unresolved party IDs" in { implicit env =>
      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      submitDirectoryEntryRequest(aliceUserParty, aliceDirectory, "alice.cns")

      browseToPaymentRequests(aliceDamlUser)
      val dirPartyId = directory.getProviderPartyId().toProtoPrimitive
      // ^^ PartyId's toString might truncate the ID, so we're explicitly using toProtoPrimitive to get the full ID.

      // Check that the directory party ID not been resolved has been handled properly, and the party ID is shown instead.
      eventually() {
        inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
          row.childElement(className("app-request-receiver")).text shouldBe dirPartyId
        }
      }
    }

    "show app payment requests, and resolve party IDs in them" in { implicit env =>
      // Register directory.cns in directory
      val dirEntryName = "directory.cns"
      val dirPartyId = directory.getProviderPartyId()
      directory.remoteParticipant.ledger_api.commands.submit(
        actAs = Seq(dirPartyId),
        commands = Seq(
          dirCodegen
            .DirectoryEntry(
              user = dirPartyId.toPrim,
              provider = dirPartyId.toPrim,
              name = dirEntryName,
            )
            .create
            .command
        ),
        optTimeout = None,
      )

      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      submitDirectoryEntryRequest(aliceUserParty, aliceDirectory, "alice.cns")

      browseToPaymentRequests(aliceDamlUser)
      // Check that the directory party ID has been resolved to its directory entry correctly.
      // We do this in another eventually() as a "..." text might appear momentarily, until the directory service responds.
      eventually() {
        inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
          val expected = s"directory.cns (${dirPartyId.toProtoPrimitive})"
          row.childElement(className("app-request-receiver")).text shouldBe expected
          row.childElement(className("app-request-provider")).text shouldBe expected
          row
        }
      }
    }
  }

  private def setupForTestWithDirectory(damlUser: String, validator: LocalValidatorAppReference) = {
    validator.remoteParticipant.dars.upload(directoryDarPath)
    validator.onboardUser(damlUser)
  }

  private def submitDirectoryEntryRequest(
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      dirEntry: String,
  )(implicit env: CoinTestConsoleEnvironment) = {
    // Whitelist the directory service on alice's validator
    directory.requestDirectoryInstall()
    eventually() {
      aliceDirectory.ledgerApi.ledger_api.acs.await(userParty, dirCodegen.DirectoryInstall)
    }
    aliceDirectory.requestDirectoryEntry(dirEntry)
  }

  private def browseToPaymentRequests(damlUser: String) = {
    // Go to app payment requests tab in alice's wallet
    go to "http://localhost:3000"
    click on "user-id-field"
    textField("user-id-field").value = damlUser
    click on "login-button"
    click on "payment-requests-tab"
  }
}
