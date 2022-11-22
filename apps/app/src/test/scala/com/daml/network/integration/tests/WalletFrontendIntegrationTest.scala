package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.wallet.{subscriptions => subsCodegen}
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{LocalValidatorAppReference, RemoteDirectoryAppReference}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.WebDriver

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*
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
        val row = inside(findAll(className("coins-table-row")).toList) { case Seq(row) => row }
        val quantity = row.childElement(className("coins-table-quantity"))
        quantity.text should be("15.0000000000CC")
      }
    }

    "correctly handle different number formats and non-numeric entries for tap" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceValidator.onboardUser(aliceDamlUser)

      withFrontEnd("alice") { implicit webDriver =>
        go to "http://localhost:3000"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on "tap-amount-field"

        textField("tap-amount-field").value = "25"
        click on "tap-button"
        textField("tap-amount-field").value = "35."
        click on "tap-button"
        textField("tap-amount-field").value = "45.0"
        click on "tap-button"
        eventually() {
          val quantities = findAll(className("coins-table-row")).toList.map(row =>
            row.childElement(className("coins-table-quantity")).text
          )
          quantities should contain theSameElementsAs Seq(
            "25.0000000000CC",
            "35.0000000000CC",
            "45.0000000000CC",
          )
        }

        textField("tap-amount-field").value = "test"
        find(id("tap-button")).getOrElse(fail()).isEnabled shouldBe false
        find(id("tap-amount-field-label"))
          .getOrElse(fail())
          .underlying
          .getAttribute("class") should include("Mui-error")
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
        // The onboard button should immediately be disabled, to prevent further clicking.
        find(id("onboard-button")) match {
          case Some(e) => e.isEnabled shouldBe false
          case _ => // The page went back to the default view before we could check the button
        }

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
      val expectedDirName = createDirectoryEntryForDirectoryItself

      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      submitDirectoryEntryRequest(aliceUserParty, aliceDirectory, "alice.cns")

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)
        // Check that the directory party ID has been resolved to its directory entry correctly.
        // We do this in another eventually() as a "..." text might appear momentarily, until the directory service responds.
        eventually() {
          inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
            row.childElement(className("app-request-receiver")).text should matchText(
              expectedDirName
            )
          }
          inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
            row.childElement(className("app-request-provider")).text should matchText(
              expectedDirName
            )
            row
          }
        }
      }
    }

    "show app payment requests, including quantity and currency code" in { implicit env =>
      val expectedDirName = createDirectoryEntryForDirectoryItself

      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      submitDirectoryEntryRequest(aliceUserParty, aliceDirectory, "alice.cns")

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)
        // Check that the directory party ID has been resolved to its directory entry correctly.
        // We do this in another eventually() as a "..." text might appear momentarily, until the directory service responds.
        eventually() {
          inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
            row.childElement(className("app-request-receiver")).text should matchText(
              expectedDirName
            )
            row.childElement(className("app-request-payment-quantity")).text should matchText(
              "1.0000000000CC"
            )
          }
        }
      }
    }

    "show subscription requests and allow users to accept them" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceRemoteWallet.tap(50) // she'll need this for accepting the subscription request
      requestDirectoryEntryWithSubscription(aliceUserParty, aliceDirectory, "alice.cns")

      withFrontEnd("alice") { implicit webDriver =>
        browseToSubscriptions(aliceDamlUser)
        clue("Check that the subscription request is listed") {
          eventually() {
            inside(findAll(className("sub-requests-table-row")).toList) { case Seq(row) =>
              row.childElement(className("sub-request-receiver")).text should matchText(
                expectedDirName
              )
              row.childElement(className("sub-request-provider")).text should matchText(
                expectedDirName
              )
            }
          }
        }
        click on className("sub-request-accept-button")
        eventually() {
          findAll(className("sub-requests-table-row")) shouldBe empty
        }
      }
    }

    "show subscriptions in different states" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceRemoteWallet.tap(50) // she'll need this for accepting and financing subscriptions

      val expectedIdleText = "Waiting for next payment to become due"
      val expectedPaymentText = "Payment in progress"

      withFrontEnd("alice") { implicit webDriver =>
        clue("Create a subscription for registering alice.cns") {
          requestDirectoryEntryWithSubscription(aliceUserParty, aliceDirectory, "alice.cns")
          browseToSubscriptions(aliceDamlUser)
          eventually() {
            click on className("sub-request-accept-button")
          }
        }
        clue("Check that the subscription is listed as idle") {
          eventually() {
            inside(findAll(className("subs-table-row")).toList) {
              case Seq(row) => {
                row.childElement(className("sub-receiver")).text should matchText(expectedDirName)
                row.childElement(className("sub-provider")).text should matchText(expectedDirName)
                row.childElement(className("sub-state")).text should matchText(expectedIdleText)
              }
            }
          }
        }
        clue("Create second subscription, the payment on which won't be collected") {
          createSelfSubscription(aliceUserParty, nextPaymentDueAt = Instant.now())
        }
        clue(
          "Wait until the wallet backend triggers the next subscription payment, then " +
            "check that the changed subscription state is visible and the cancellation buttons are enabled correctly"
        ) {
          eventually() {
            val rows = findAll(className("subs-table-row")).toList
            rows.length shouldBe 2
            rows
              .filter(row => row.childElement(className("sub-state")).text == expectedIdleText)
              .filter(row => row.childElement(className("sub-cancel-button")).isEnabled)
              .length shouldBe 1
            rows
              .filter(row => row.childElement(className("sub-state")).text == expectedPaymentText)
              .filter(row => !row.childElement(className("sub-cancel-button")).isEnabled)
              .length shouldBe 1
          }
        }
      }
    }

    "support canceling subscriptions" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceDamlUser, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceRemoteWallet.tap(50) // she'll need this for accepting and financing subscriptions

      val expectedIdleText = "Waiting for next payment to become due"

      withFrontEnd("alice") { implicit webDriver =>
        clue("Create subscription") {
          requestDirectoryEntryWithSubscription(aliceUserParty, aliceDirectory, "alice1.cns")
          browseToSubscriptions(aliceDamlUser)
          eventually() {
            click on className("sub-request-accept-button")
          }
        }
        clue("Check that the first subscription is listed") {
          eventually() {
            inside(findAll(className("subs-table-row")).toList) {
              case Seq(row) => {
                row.childElement(className("sub-receiver")).text should matchText(expectedDirName)
                row.childElement(className("sub-provider")).text should matchText(expectedDirName)
                row.childElement(className("sub-state")).text should matchText(expectedIdleText)
              }
            }
          }
        }
        clue("Cancel subscription") {
          click on className("sub-cancel-button")
        }
        clue("Check that the subscription is no longer listed") {
          eventually() {
            findAll(className("subs-table-row")).toSeq shouldBe empty;
          }
        }
      }
    }
  }

  private def setupForTestWithDirectory(damlUser: String, validator: LocalValidatorAppReference) = {
    validator.remoteParticipant.dars.upload(directoryDarPath)
    validator.onboardUser(damlUser)
  }

  private def createDirectoryEntryForDirectoryItself(implicit
      env: CoinTestConsoleEnvironment
  ): String = {
    val dirEntryName = "directory.cns"
    val dirParty = directory.getProviderPartyId()
    directory.remoteParticipant.ledger_api.commands.submitJava(
      actAs = Seq(dirParty),
      commands = new dirCodegen.DirectoryEntry(
        dirParty.toProtoPrimitive,
        dirParty.toProtoPrimitive,
        dirEntryName,
        Instant.now().plus(90, ChronoUnit.DAYS),
      ).create.commands.asScala.toSeq,
      optTimeout = None,
    )
    expectedCns(dirParty, dirEntryName)
  }

  private def submitDirectoryEntryRequest(
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      dirEntry: String,
  )(implicit env: CoinTestConsoleEnvironment) = {
    // Whitelist the directory service on alice's validator
    directory.requestDirectoryInstall()
    eventually() {
      aliceDirectory.ledgerApi.ledger_api.acs
        .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(userParty)
    }
    aliceDirectory.requestDirectoryEntry(dirEntry)
  }

  private def requestDirectoryEntryWithSubscription(
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      dirEntry: String,
  )(implicit env: CoinTestConsoleEnvironment) = {
    // Whitelist the directory service on alice's validator
    directory.requestDirectoryInstall()
    eventually() {
      aliceDirectory.ledgerApi.ledger_api.acs
        .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(userParty)
    }
    aliceDirectory.requestDirectoryEntryWithSubscription(dirEntry)
  }

  private def createSelfSubscription(aliceUserParty: PartyId, nextPaymentDueAt: Instant)(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val context = new testSubsCodegen.TestSubscriptionContext(
      scan.getSvcPartyId().toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      "description",
    )
    val contextId = clue("Create a subscription context") {
      aliceWallet.remoteParticipant.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = context.create.commands.asScala.toSeq,
      )
      aliceWallet.remoteParticipant.ledger_api.acs
        .awaitJava(testSubsCodegen.TestSubscriptionContext.COMPANION)(
          aliceUserParty,
          _.data == context,
        )
        .id
    }
    val (subscriptionId, subscriptionData) = clue("Create a subscription") {
      val subscription = new subsCodegen.Subscription(
        aliceUserParty.toProtoPrimitive,
        aliceUserParty.toProtoPrimitive,
        aliceUserParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
      )
      aliceWallet.remoteParticipant.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = subscription.create.commands.asScala.toSeq,
      )
      val subscriptionId = aliceWallet.remoteParticipant.ledger_api.acs
        .awaitJava(subsCodegen.Subscription.COMPANION)(aliceUserParty, _.data == subscription)
        .id
      (subscriptionId, subscription)
    }
    clue("Create a subscription idle state") {
      val payData = new subsCodegen.SubscriptionPayData(
        BigDecimal(10).bigDecimal.setScale(10),
        new RelTime(60 * 60 * 1000000L),
        new RelTime(10 * 60 * 1000000L),
        new RelTime(60 * 1000000L),
      )
      val state = new subsCodegen.SubscriptionIdleState(
        subscriptionId,
        subscriptionData,
        payData,
        nextPaymentDueAt,
      )
      aliceWallet.remoteParticipant.ledger_api.commands.submitJava(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = state.create.commands.asScala.toSeq,
      )
    }
  }

  private def browseToPaymentRequests(damlUser: String)(implicit webDriver: WebDriver) = {
    // Go to app payment requests tab in alice's wallet
    go to "http://localhost:3000"
    click on "user-id-field"
    textField("user-id-field").value = damlUser
    click on "login-button"
    click on "app-payment-requests-button"
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
