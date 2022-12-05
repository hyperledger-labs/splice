package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{
  LocalValidatorAppReference,
  RemoteDirectoryAppReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.CoinTestUtil
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.WebDriver

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

class WalletFrontendIntegrationTest
    extends FrontendIntegrationTest("alice", "bob")
    with CoinTestUtil {

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  "A wallet UI" should {

    "allow tapping coins and then list the created coins" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      onboardWalletUser(this, aliceWallet, aliceValidator)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
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
      val aliceDamlUser = aliceWallet.config.damlUser
      onboardWalletUser(this, aliceWallet, aliceValidator)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
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
      val newRandomUser = aliceWallet.config.damlUser

      withFrontEnd("alice") { implicit webDriver =>
        // Do not use browseToWallet below, because that waits for the user to be logged in, which is not the case here
        go to s"http://localhost:3000"
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
      val aliceDamlUser = aliceWallet.config.damlUser
      val entryName = "alice.cns"
      val aliceParty = setupForTestWithDirectory(this, aliceWallet, aliceValidator)
      submitDirectoryEntryRequest(aliceParty, aliceDirectory, entryName)

      def getPaymentRequest() = aliceWallet.listAppPaymentRequests().headOption

      aliceWallet.tap(5.0)
      val walletPaymentRequest = eventually()(
        getPaymentRequest().getOrElse(fail("Payment request is unexpectedly not defined"))
      )
      val _ = aliceWallet.acceptAppPaymentRequest(walletPaymentRequest.contractId)

      def tryGetEntry() =
        Try(loggerFactory.suppressErrors(directory.lookupEntryByName(entryName)))

      eventually()(tryGetEntry().getOrElse(fail(s"Could not get entry $entryName")))

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)

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
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(this, aliceWallet, aliceValidator)
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
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(this, aliceWallet, aliceValidator)
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
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(this, aliceWallet, aliceValidator)
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
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(this, aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting the subscription request
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
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(this, aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting and financing subscriptions

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
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(this, aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting and financing subscriptions

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

    "support transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceParty = setupForTestWithDirectory(this, aliceWallet, aliceValidator)
      val aliceEntryName = "alice.cns"
      actAndCheck("Tap coin for alice", aliceWallet.tap(50))(
        "Alice has coin",
        _ => (aliceWallet.list().coins.length shouldBe 1),
      )
      createDirectoryEntry(aliceParty, aliceDirectory, aliceEntryName, aliceWallet)
      val bobDamlUser = bobWallet.config.damlUser
      val bobParty = setupForTestWithDirectory(this, bobWallet, bobValidator)
      val bobEntryName = "bob.cns"
      actAndCheck("Tap coin for bob", bobWallet.tap(50))(
        "Bob has coin",
        _ => (bobWallet.list().coins.length shouldBe 1),
      )
      createDirectoryEntry(bobParty, bobDirectory, bobEntryName, bobWallet)

      def createOffer(description: String)(implicit webDriver: WebDriver) = {
        actAndCheck(
          s"Alice creates offer \"${description}\"", {
            click on "create-offer-receiver"
            textField("create-offer-receiver").value = bobParty.toProtoPrimitive
            click on "create-offer-quantity"
            textField("create-offer-quantity").value = "100.0"
            click on "create-offer-description"
            textField("create-offer-description").value = description
            click on "create-offer-button"
          },
        )(
          "Alice sees the transfer offer",
          _ => {
            findAll(className("transfer-offers-row")) should have size 1
          },
        )

      }

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        click on "transfer-offers-button"

        createOffer("Testing transfer offers")
        val row = inside(findAll(className("transfer-offers-row")).toList) { case Seq(row) => row }
        row.childElement(className("transfer-offers-table-quantity")).text should be(
          "100.0000000000CC"
        )
      }

      withFrontEnd("bob") { implicit webDriver =>
        browseToBobWallet(bobDamlUser)
        actAndCheck("Bob browses to transfer offers", click on "transfer-offers-button")(
          "Bob also sees the transfer offer",
          _ => {
            findAll(className("transfer-offers-row")) should have size 1
          },
        )
        actAndCheck("Bob accepts the offer", click on className("transfer-offers-table-accept"))(
          "Bob sees the accepted offer (not enough funds for it to complete)",
          _ => {
            findAll(className("transfer-offers-row")) should have size 0
            findAll(className("accepted-transfer-offers-row")) should have size 1
          },
        )
      }

      withFrontEnd("alice") { implicit webDriver =>
        clue("Alice also sees the accepted offer") {
          eventually() {
            findAll(className("accepted-transfer-offers-row")) should have size 1
          }
        }

        actAndCheck("Tap more coin for alice", aliceWallet.tap(100))(
          "Accepted offer is completed",
          _ => {
            findAll(className("accepted-transfer-offers-row")) should have size 0
          },
        )

        createOffer("to be withdrawn")
        actAndCheck(
          "Alice withdraws her offer",
          click on className("transfer-offers-table-withdraw"),
        )("The offer is deleted", _ => findAll(className("transfer-offers-row")) should have size 0)

        createOffer("to be rejected")
      }

      withFrontEnd("bob") { implicit webDrivers =>
        eventually()(findAll(className("transfer-offers-row")) should have size 1)
        actAndCheck("Bob rejects the offer", click on className("transfer-offers-table-reject"))(
          "The offer is deleted",
          _ => findAll(className("transfer-offers-row")) should have size 0,
        )
      }

    }

    "user name is persisted" in { implicit env =>
      val aliceParty = onboardWalletUser(this, aliceWallet, aliceValidator)
      withFrontEnd("alice") { implicit webDrivers =>
        browseToAliceWallet(aliceWallet.config.damlUser)
        find(id("logged-in-user")).value.text should matchText(aliceParty.toProtoPrimitive)
        actAndCheck(
          "Alice reloads the page", {
            go to s"http://localhost:3000"
          },
        )(
          "Alice is automatically logged in",
          _ => find(id("logged-in-user")).value.text should matchText(aliceParty.toProtoPrimitive),
        )
        actAndCheck(
          "Alice logs out", {
            click on "logout-button"
          },
        )("Alice sees the login screen again", _ => find(id("login-button")) should not be empty)
      }
    }
  }

  private def setupForTestWithDirectory(
      test: BaseTest,
      walletClient: WalletAppClientReference,
      validator: LocalValidatorAppReference,
  ) = {
    validator.remoteParticipant.dars.upload(directoryDarPath)
    onboardWalletUser(test, walletClient, validator)
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
  ) = {
    // Whitelist the directory service on alice's validator
    directory.requestDirectoryInstall()
    eventually() {
      directory.ledgerApi.ledger_api.acs
        .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(userParty)
    }
    directory.requestDirectoryEntry(dirEntry)
  }

  private def createDirectoryEntry(
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      dirEntry: String,
      wallet: WalletAppClientReference,
  ) = {
    submitDirectoryEntryRequest(userParty, directory, dirEntry)
    wallet.tap(5.0)
    eventually() {
      wallet.listAppPaymentRequests().length shouldBe 1
    }
    wallet.acceptAppPaymentRequest(
      wallet.listAppPaymentRequests().head.contractId
    )
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
      aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = context.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipant.ledger_api.acs
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
      aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = subscription.create.commands.asScala.toSeq,
      )
      val subscriptionId = aliceWalletBackend.remoteParticipant.ledger_api.acs
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
      aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = state.create.commands.asScala.toSeq,
      )
    }
  }

  private def browseToWallet(port: Int, damlUser: String)(implicit webDriver: WebDriver) = {
    actAndCheck(
      s"Browse to wallet UI at port ${port}", {
        go to s"http://localhost:${port}"
        click on "user-id-field"
        textField("user-id-field").value = damlUser
        click on "login-button"
      },
    )(
      "Logged in user shows up",
      _ => find(id("logged-in-user")).getOrElse(fail("Logged-in user information never showed up")),
    )
  }

  private def browseToAliceWallet(damlUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3000, damlUser)
  }

  private def browseToBobWallet(damlUser: String)(implicit webDriver: WebDriver) = {
    browseToWallet(3001, damlUser)
  }

  private def browseToPaymentRequests(damlUser: String)(implicit webDriver: WebDriver) = {
    // Go to app payment requests tab in alice's wallet
    browseToAliceWallet(damlUser)
    click on "app-payment-requests-button"
  }

  private def browseToSubscriptions(damlUser: String)(implicit webDriver: WebDriver) = {
    // Go to subscriptions tab in alice's wallet
    browseToAliceWallet(damlUser)
    click on "subscriptions-button"
  }
}
