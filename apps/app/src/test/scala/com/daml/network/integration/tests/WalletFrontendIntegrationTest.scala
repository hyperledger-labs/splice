package com.daml.network.integration.tests

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.Scripts.Wallet.{TestSubscriptions => testSubsCodegen}
import com.daml.network.codegen.CN.Wallet.{Subscriptions => subsCodegen}
import com.daml.network.codegen.CN.{Directory => dirCodegen}
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.console.{LocalValidatorAppReference, RemoteDirectoryAppReference}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.WebDriver

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.Try

class WalletFrontendIntegrationTest extends FrontendIntegrationTest("alice") {

  private val directoryDarPath =
    "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"

  "A wallet UI" should {

    "allow tapping coins and then list the created coins" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)

      withFrontEnd("alice") { implicit webDriver =>
        go to "http://localhost:3000"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on "tap-amount-field"
        textField("tap-amount-field").value = "15.0"
        click on "tap-button"
        eventually() {
          findAll(className("coins-table-row")) should have size 1
        }
        val row = inside(findAll(className("coins-table-row")).toList) { case Seq(row) =>
          row
        }
        val quantity = row.childElement(className("coins-table-quantity"))
        quantity.text should be("15.0000000000")
      }
    }

    "allow a random user to onboard themselves, then tap and list coins" in { implicit env =>
      // Note: the test generates a unique user for each test
      val newRandomUser = aliceRemoteWallet.config.damlUser

      withFrontEnd("alice") { implicit webDriver =>
        go to "http://localhost:3000"
        click on "user-id-field"
        textField("user-id-field").value = newRandomUser
        click on "login-button"

        // After a short delay, the UI should realize that the user is not onboarded,
        // and switch to the onbaording page.
        click on "onboard-button"
        // TODO(i1139): The UI should handle the user repeatedly clicking on the onboarding button,
        // which may or may not disable/disappar after the first click.
        // find(id("onboard-button")).foreach(_.underlying.click())

        // After a short delay, the UI should realize that the user is now onboarded
        // and switch to the default view.
        click on "tap-amount-field"
        textField("tap-amount-field").value = "15.0"
        click on "tap-button"
        eventually() {
          findAll(className("coins-table-row")) should have size 1
        }
      }
    }

    "report errors" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)

      withFrontEnd("alice") { implicit webDriver =>
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
    }

    "show logged in user details" in { implicit env =>
      // Create directory entry for alice
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val entryName = "alice.cns"
      val aliceParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      submitDirectoryEntryRequest(aliceParty, aliceDirectory, entryName)

      def getPaymentRequest() = aliceRemoteWallet.listAppMultiPaymentRequests().headOption

      aliceRemoteWallet.tap(5.0)
      val walletPaymentRequest = eventually()(
        getPaymentRequest().getOrElse(fail("Payment request is unexpectedly not defined"))
      )
      val _ = aliceRemoteWallet.acceptAppMultiPaymentRequest(walletPaymentRequest.contractId)

      def tryGetEntry() =
        Try(loggerFactory.suppressErrors(directory.lookupEntryByName(entryName)))

      eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $entryName")))

      withFrontEnd("alice") { implicit webDriver =>
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
          find(id("logged-in-user")).value.text should matchText(
            expectedCns(aliceParty, "alice.cns")
          )
        }
      }
    }

    "show app payment requests, and correctly handle unresolved party IDs" in { implicit env =>
      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      submitDirectoryEntryRequest(aliceUserParty, aliceDirectory, "alice.cns")

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)
        val dirPartyId = directory.getProviderPartyId().toProtoPrimitive
        // ^^ PartyId's toString might truncate the ID, so we're explicitly using toProtoPrimitive to get the full ID.

        // Check that the directory party ID not been resolved has been handled properly, and the party ID is shown instead.
        eventually() {
          inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
            row.childElement(className("app-request-receiver")).text shouldBe dirPartyId
          }
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
              expiresAt = Primitive.Timestamp
                .discardNanos(Instant.now().plus(90, ChronoUnit.DAYS))
                .getOrElse(fail("Failed to convert timestamp")),
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

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)
        // Check that the directory party ID has been resolved to its directory entry correctly.
        // We do this in another eventually() as a "..." text might appear momentarily, until the directory service responds.
        val expected = expectedCns(dirPartyId, "directory.cns")
        eventually() {
          inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
            row.childElement(className("app-request-receiver")).text should matchText(expected)
          }
          inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
            row.childElement(className("app-request-provider")).text should matchText(expected)
            row
          }
        }
      }
    }

    "show subscription requests and allow users to accept them" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      aliceRemoteWallet.tap(50) // she'll need this for accepting the subscription request
      createSelfSubscriptionRequest(aliceUserParty);

      withFrontEnd("alice") { implicit webDriver =>
        browseToSubscriptions(aliceDamlUser)
        eventually() {
          inside(findAll(className("sub-requests-table-row")).toList) { case Seq(row) =>
            clue("Check that the subscription request is listed") {
              val expected = aliceUserParty.toProtoPrimitive
              row.childElement(className("sub-request-receiver")).text should matchText(expected)
              row.childElement(className("sub-request-provider")).text should matchText(expected)
            }
          }
        }
        click on className("sub-request-accept-button")
        eventually() {
          findAll(className("sub-requests-table-row")) shouldBe empty
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

  private def createSelfSubscriptionRequest(
      aliceUserParty: PartyId
  )(implicit env: CoinTestConsoleEnvironment) = {
    // Create a subscription context
    aliceWallet.remoteParticipant.ledger_api.commands.submit(
      Seq(aliceUserParty),
      optTimeout = None,
      commands = Seq(
        testSubsCodegen
          .TestSubscriptionContext(
            user = aliceUserParty.toPrim,
            service = aliceUserParty.toPrim,
            description = "description",
          )
          .create
          .command
      ),
    )
    val referenceId =
      aliceWallet.remoteParticipant.ledger_api.acs
        .await(aliceUserParty, testSubsCodegen.TestSubscriptionContext)
        .contractId
    // Assemble actual test data
    val subscriptionData = subsCodegen.Subscription(
      sender = aliceUserParty.toPrim,
      receiver = aliceUserParty.toPrim,
      provider = aliceUserParty.toPrim,
      svc = svcParty.toPrim,
      context = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
    )
    val payData = subsCodegen.SubscriptionPayData(
      paymentQuantity = BigDecimal(10: Int),
      paymentInterval = RelTime(microseconds = 60 * 60 * 1000000L),
      paymentDuration = RelTime(microseconds = 60 * 60 * 1000000L),
      collectionDuration = RelTime(microseconds = 60 * 1000000L),
    ) // paymentDuration == paymenInterval, so we can make a second payment immediately
    (subscriptionData, payData)
    val request = subsCodegen.SubscriptionRequest(
      subscriptionData,
      payData,
    )
    aliceWallet.remoteParticipant.ledger_api.commands.submit(
      actAs = Seq(aliceUserParty),
      optTimeout = None,
      commands = Seq(request.create.command),
    )
  }

  private def browseToPaymentRequests(damlUser: String)(implicit webDriver: WebDriver) = {
    // Go to app payment requests tab in alice's wallet
    go to "http://localhost:3000"
    click on "user-id-field"
    textField("user-id-field").value = damlUser
    click on "login-button"
    click on "app-multi-payment-requests-button"
  }

  private def browseToSubscriptions(damlUser: String)(implicit webDriver: WebDriver) = {
    // Go to subscriptions tab in alice's wallet
    go to "http://localhost:3000"
    click on "user-id-field"
    textField("user-id-field").value = damlUser
    click on "login-button"
    click on "subscriptions-button"
  }
}
