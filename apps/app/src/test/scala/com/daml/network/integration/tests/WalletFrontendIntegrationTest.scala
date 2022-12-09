package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as paymentCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{
  LocalValidatorAppReference,
  RemoteDirectoryAppReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.{Keys, WebDriver}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

class WalletFrontendIntegrationTest
    extends FrontendIntegrationTest("alice", "bob")
    with WalletTestUtil {

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  "A wallet UI" should {

    "allow tapping coins and then list the created coins" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      onboardWalletUser(aliceWallet, aliceValidator)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        click on "tap-amount-field"
        numberField("tap-amount-field").underlying.sendKeys("15.0")
        click on "tap-button"
        eventually() {
          findAll(className("coins-table-row")) should have size 1
        }
        val row = inside(findAll(className("coins-table-row")).toList) { case Seq(row) => row }
        val quantity = row.childElement(className("coins-table-quantity"))
        quantity.text should be("15.0000000000CC")
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
        numberField("tap-amount-field").underlying.sendKeys("15.0")
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
      val aliceParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      requestDirectoryEntry(aliceParty, aliceDirectory, entryName)

      def getPaymentRequest() = aliceWallet.listSubscriptionRequests().headOption

      aliceWallet.tap(5.0)
      val subscriptionRequest = eventually()(
        getPaymentRequest().getOrElse(fail("Payment request is unexpectedly not defined"))
      )
      val _ = aliceWallet.acceptSubscriptionRequest(subscriptionRequest.contractId)

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

    "show app payment requests in CC, and correctly handle unresolved party IDs" in {
      implicit env =>
        // Alice submits a directory entry request, which will create an app payment request in her wallet
        val aliceDamlUser = aliceWallet.config.damlUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        createSelfPaymentRequest(aliceUserParty, 42, paymentCodegen.Currency.CC)

        withFrontEnd("alice") { implicit webDriver =>
          browseToPaymentRequests(aliceDamlUser)

          // Verify that the total quantity of CC is properly displayed
          eventually() {
            inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
              // Verify that the currency and quantity are properly displayed
              row.childElement(className("app-request-total-quantity")).text should matchText(
                "42.00000000CC"
              )
            }
          }

          // Verify that the receiver table row is properly displayed
          eventually() {
            inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
              // Check that alice party ID could not be resolved against the directory
              // service, and the party ID is shown instead.
              row
                .childElement(className("app-request-receiver"))
                .text shouldBe aliceUserParty.toProtoPrimitive

              // Verify that the currency and quantity are properly displayed
              row.childElement(className("app-request-payment-quantity")).text should matchText(
                "42.0000000000CC"
              )
            }
          }
        }
    }

    "show app payment requests in USD (with USD==CC), and correctly handle unresolved party IDs" in {
      implicit env =>
        // Alice submits a directory entry request, which will create an app payment request in her wallet
        val aliceDamlUser = aliceWallet.config.damlUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        createSelfPaymentRequest(aliceUserParty, 42, paymentCodegen.Currency.USD)

        withFrontEnd("alice") { implicit webDriver =>
          browseToPaymentRequests(aliceDamlUser)

          // Verify that the total quantity of USD is properly displayed
          eventually() {
            inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
              // Verify that the currency and quantity are properly displayed
              row.childElement(className("app-request-total-quantity")).text should matchText(
                "42.00000000CC"
              )
            }
          }

          // Verify that the receiver table row is properly displayed
          eventually() {
            inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
              // Check that alice party ID could not be resolved against the directory
              // service, and the party ID is shown instead.
              row
                .childElement(className("app-request-receiver"))
                .text shouldBe aliceUserParty.toProtoPrimitive

              // Verify that the currency and quantity are properly displayed
              row.childElement(className("app-request-payment-quantity")).text should matchText(
                "42.0000000000USD"
              )
            }
          }
        }
    }

    "show app payment requests with multiple receivers (CC only)" in { implicit env =>
      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)

      createPaymentRequest(
        aliceUserParty,
        Seq(
          receiverQuantity(aliceUserParty, 22, paymentCodegen.Currency.CC),
          receiverQuantity(aliceUserParty, 20, paymentCodegen.Currency.CC),
        ),
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)

        // Verify that the total quantity of USD is properly displayed
        eventually() {
          inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
            // Verify that the currency and quantity are properly displayed
            row.childElement(className("app-request-total-quantity")).text should matchText(
              "42.00000000CC"
            )
          }
        }

        // Verify that the receiver table rows contain both receiver quantities
        eventually() {
          val quantities =
            findAll(className("receiver-quantity-row")).toList.map(row =>
              row.childElement(className("app-request-payment-quantity")).text
            )

          quantities should contain theSameElementsAs Seq(
            "22.0000000000CC",
            "20.0000000000CC",
          )
        }
      }
    }

    "show app payment requests with multiple receivers (USD only, coin price of 1.0)" in {
      implicit env =>
        // Alice submits a directory entry request, which will create an app payment request in her wallet
        val aliceDamlUser = aliceWallet.config.damlUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)

        createPaymentRequest(
          aliceUserParty,
          Seq(
            receiverQuantity(aliceUserParty, 22, paymentCodegen.Currency.USD),
            receiverQuantity(aliceUserParty, 20, paymentCodegen.Currency.USD),
          ),
        )

        withFrontEnd("alice") { implicit webDriver =>
          browseToPaymentRequests(aliceDamlUser)

          // Verify that the total quantity of USD is properly displayed
          eventually() {
            inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
              // Verify that the currency and quantity are properly displayed
              row.childElement(className("app-request-total-quantity")).text should matchText(
                "42.00000000CC"
              )
            }
          }

          // Verify that the receiver table rows contain both receiver quantities
          eventually() {
            val quantities =
              findAll(className("receiver-quantity-row")).toList.map(row =>
                row.childElement(className("app-request-payment-quantity")).text
              )

            quantities should contain theSameElementsAs Seq(
              "22.0000000000USD",
              "20.0000000000USD",
            )
          }
        }
    }

    "show app payment requests with multiple receivers (USD and CC, coin price of 1.0)" in {
      implicit env =>
        // Alice submits a directory entry request, which will create an app payment request in her wallet
        val aliceDamlUser = aliceWallet.config.damlUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)

        createPaymentRequest(
          aliceUserParty,
          Seq(
            receiverQuantity(aliceUserParty, 22, paymentCodegen.Currency.USD),
            receiverQuantity(aliceUserParty, 20, paymentCodegen.Currency.CC),
          ),
        )

        withFrontEnd("alice") { implicit webDriver =>
          browseToPaymentRequests(aliceDamlUser)

          // Verify that the total quantity of USD is properly displayed
          eventually() {
            inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
              // Verify that the currency and quantity are properly displayed
              row.childElement(className("app-request-total-quantity")).text should matchText(
                "42.00000000CC"
              )
            }
          }

          // Verify that the receiver table rows contain both receiver quantities
          eventually() {
            val quantities =
              findAll(className("receiver-quantity-row")).toList.map(row =>
                row.childElement(className("app-request-payment-quantity")).text
              )

            quantities should contain theSameElementsAs Seq(
              "22.0000000000USD",
              "20.0000000000CC",
            )
          }
        }
    }

    "show app payment requests, and resolve party IDs in them" in { implicit env =>
      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceDirectoryName = "alice.cns"
      val aliceDirectoryDisplay = expectedCns(aliceUserParty, aliceDirectoryName)
      createDirectoryEntry(aliceUserParty, aliceDirectory, "alice.cns", aliceWallet)
      createSelfPaymentRequest(aliceUserParty, 42, paymentCodegen.Currency.CC)

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)
        // Check that the directory party ID has been resolved to its directory entry correctly.
        // We do this in another eventually() as a "..." text might appear momentarily, until the directory service responds.
        eventually() {
          inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
            row.childElement(className("app-request-receiver")).text should matchText(
              aliceDirectoryDisplay
            )
          }
          inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
            row.childElement(className("app-request-provider")).text should matchText(
              aliceDirectoryDisplay
            )
            row
          }
        }
      }
    }

    "show subscription requests and allow users to accept them" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting the subscription request
      requestDirectoryEntry(aliceUserParty, aliceDirectory, "alice.cns")

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
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting and financing subscriptions

      val expectedIdleText = "Waiting for next payment to become due"
      val expectedPaymentText = "Payment in progress"

      withFrontEnd("alice") { implicit webDriver =>
        clue("Create a subscription for registering alice.cns") {
          requestDirectoryEntry(aliceUserParty, aliceDirectory, "alice.cns")
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
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting and financing subscriptions

      val expectedIdleText = "Waiting for next payment to become due"

      withFrontEnd("alice") { implicit webDriver =>
        clue("Create subscription") {
          requestDirectoryEntry(aliceUserParty, aliceDirectory, "alice1.cns")
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

    "support different ways of defining the receiver in transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = "alice.cns"
      actAndCheck("Tap coin for alice", aliceWallet.tap(50))(
        "Alice has coin",
        _ => (aliceWallet.list().coins.length shouldBe 1),
      )
      createDirectoryEntry(aliceParty, aliceDirectory, aliceEntryName, aliceWallet)
      val bobParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobCns = "bob.cns"
      val bobEntryName = bobCns
      actAndCheck("Tap coin for bob", bobWallet.tap(50))(
        "Bob has coin",
        _ => (bobWallet.list().coins.length shouldBe 1),
      )
      createDirectoryEntry(bobParty, bobDirectory, bobEntryName, bobWallet)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        click on "transfer-offers-button"

        actAndCheck(
          s"Alice creates offer by cns name", {
            click on "create-offer-button"
            click on "create-offer-receiver"
            textField("create-offer-receiver").value = bobCns
            click on "create-offer-quantity"
            numberField("create-offer-quantity").underlying.sendKeys("100.0")
            click on "create-offer-description"
            textField("create-offer-description").value = "by party ID"
            click on "submit-create-offer-button"
          },
        )(
          "Alice sees the transfer offer",
          _ => {
            findAll(className("transfer-offers-row")) should have size 1
          },
        )

        actAndCheck(
          s"Alice creates offer with auto-complete", {
            click on "create-offer-button"
            click on "create-offer-receiver"
            textField("create-offer-receiver").value = "b"
            textField("create-offer-receiver").underlying.sendKeys(Keys.ARROW_DOWN)
            textField("create-offer-receiver").underlying.sendKeys(Keys.RETURN)
            click on "create-offer-quantity"
            numberField("create-offer-quantity").underlying.sendKeys("100.0")
            click on "create-offer-description"
            textField("create-offer-description").value = "with auto-complete"
            click on "submit-create-offer-button"
          },
        )(
          "Alice sees the transfer offer",
          _ => {
            findAll(className("transfer-offers-row")) should have size 2
          },
        )

        // The case of inserting the whole party ID is tested in "support transfer offers"

        clue("Bob received both offers") {
          eventually() {
            bobWallet.listTransferOffers() should have length 2
          }
        }
      }
    }

    "support transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = "alice.cns"
      actAndCheck("Tap coin for alice", aliceWallet.tap(50))(
        "Alice has coin",
        _ => (aliceWallet.list().coins.length shouldBe 1),
      )
      createDirectoryEntry(aliceParty, aliceDirectory, aliceEntryName, aliceWallet)
      val bobDamlUser = bobWallet.config.damlUser
      val bobParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobEntryName = "bob.cns"
      actAndCheck("Tap coin for bob", bobWallet.tap(50))(
        "Bob has coin",
        _ => (bobWallet.list().coins.length shouldBe 1),
      )
      createDirectoryEntry(bobParty, bobDirectory, bobEntryName, bobWallet)

      def createOffer(description: String)(implicit webDriver: WebDriver) = {
        actAndCheck(
          s"Alice creates offer \"${description}\"", {
            click on "create-offer-button"
            click on "create-offer-receiver"
            textField("create-offer-receiver").value = bobParty.toProtoPrimitive
            click on "create-offer-quantity"
            numberField("create-offer-quantity").underlying.sendKeys("100.0")
            click on "create-offer-description"
            textField("create-offer-description").value = description
            click on "submit-create-offer-button"
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
          "Alice withdraws her offer", {
            click on className("transfer-offers-table-withdraw")
          },
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

    "display currency in subscriptions and subscription requests" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val usdQuantity = new paymentCodegen.PaymentQuantity(
        BigDecimal(42.0).bigDecimal,
        paymentCodegen.Currency.USD,
      )
      val dueAt = Instant.now().plus(1, ChronoUnit.DAYS)
      createSelfSubscription(aliceUserParty, nextPaymentDueAt = dueAt, quantity = usdQuantity)
      createSelfSubscriptionRequest(
        aliceUserParty,
        nextPaymentDueAt = dueAt,
        quantity = usdQuantity,
      )
      createSelfPaymentRequest(aliceUserParty, 42, paymentCodegen.Currency.CC)
      withFrontEnd("alice") { implicit webDriver =>
        browseToSubscriptions(aliceDamlUser)
        clue("Check that the subscription request displays the currency") {
          eventually() {
            inside(findAll(className("sub-requests-table-row")).toList) { case Seq(row) =>
              row.childElement(className("sub-request-quantity")).text should matchText(
                "42.0000000000USD"
              )
            }
          }
        }
        clue("Check that the subscription displays the currency") {
          eventually() {
            inside(findAll(className("subs-table-row")).toList) { case Seq(row) =>
              row.childElement(className("sub-quantity")).text should matchText("42.0000000000USD")
            }
          }
        }
      }
    }

    "user name is persisted" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWallet, aliceValidator)
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
      walletClient: WalletAppClientReference,
      validator: LocalValidatorAppReference,
  ) = {
    validator.remoteParticipant.dars.upload(directoryDarPath)
    onboardWalletUser(walletClient, validator)
  }

  private def createDirectoryEntryForDirectoryItself(implicit
      env: CoinTestConsoleEnvironment
  ): String = {
    val dirEntryName = "directory.cns"
    val dirParty = directory.getProviderPartyId()
    directory.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
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

  private def createDirectoryEntry(
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      dirEntry: String,
      wallet: WalletAppClientReference,
  ) = {
    requestDirectoryEntry(userParty, directory, dirEntry)
    wallet.tap(5.0)
    eventually() {
      wallet.listSubscriptionRequests() should have length 1
    }
    wallet.acceptSubscriptionRequest(
      wallet.listSubscriptionRequests().head.contractId
    )
  }

  private def requestDirectoryEntry(
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

  def createTestDeliveryOffer(
      aliceUserParty: PartyId
  )(implicit env: CoinTestConsoleEnvironment) = {
    val deliveryOffer = new testWalletCodegen.TestDeliveryOffer(
      scan.getSvcPartyId().toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      "description",
    )
    clue("Create delivery offer") {
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = deliveryOffer.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.acs
        .awaitJava(testWalletCodegen.TestDeliveryOffer.COMPANION)(
          aliceUserParty,
          _.data == deliveryOffer,
        )
        .id
    }
  }

  def receiverQuantity(
      receiverParty: PartyId,
      quantity: Int,
      currency: paymentCodegen.Currency,
  ) =
    new paymentCodegen.ReceiverQuantity(
      receiverParty.toProtoPrimitive,
      new paymentCodegen.PaymentQuantity(
        BigDecimal(quantity).bigDecimal,
        currency,
      ),
    )

  def createPaymentRequest(
      aliceUserParty: PartyId,
      receiverQuantities: Seq[paymentCodegen.ReceiverQuantity],
  )(implicit env: CoinTestConsoleEnvironment) = {
    val deliveryOfferId = createTestDeliveryOffer(aliceUserParty)

    clue("Create a payment request") {
      val paymentRequest = new paymentCodegen.AppPaymentRequest(
        aliceUserParty.toProtoPrimitive,
        receiverQuantities.asJava,
        aliceUserParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        Instant.now().plus(5, ChronoUnit.MINUTES), // expires in 5 min
        new RelTime(5 * 60 * 1000000L), // 5min collection duration.
        deliveryOfferId.toInterface(paymentCodegen.DeliveryOffer.INTERFACE),
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = paymentRequest.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.acs
        .awaitJava(paymentCodegen.AppPaymentRequest.COMPANION)(aliceUserParty)
        .id
    }
  }

  def createSelfPaymentRequest(
      aliceUserParty: PartyId,
      quantity: Int,
      currency: paymentCodegen.Currency,
  )(implicit env: CoinTestConsoleEnvironment) = {
    val receiverQuantities = Seq(
      receiverQuantity(aliceUserParty, quantity, currency)
    )

    createPaymentRequest(aliceUserParty, receiverQuantities)
  }

  private val defaultSubscriptionQuantity = new paymentCodegen.PaymentQuantity(
    BigDecimal(10).bigDecimal.setScale(10),
    paymentCodegen.Currency.CC,
  )

  private def createSelfSubscriptionContext(aliceUserParty: PartyId)(implicit
      env: CoinTestConsoleEnvironment
  ): testSubsCodegen.TestSubscriptionContext.ContractId = {
    val context = new testSubsCodegen.TestSubscriptionContext(
      scan.getSvcPartyId().toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      "description",
    )
    clue("Create a subscription context") {
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = context.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.acs
        .awaitJava(testSubsCodegen.TestSubscriptionContext.COMPANION)(
          aliceUserParty,
          _.data == context,
        )
        .id
    }
  }

  private def createSelfSubscriptionData(
      contextId: testSubsCodegen.TestSubscriptionContext.ContractId,
      aliceUserParty: PartyId,
      nextPaymentDueAt: Instant,
      quantity: paymentCodegen.PaymentQuantity,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val subscription = new subsCodegen.Subscription(
      aliceUserParty.toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      aliceUserParty.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
    )
    val payData = new subsCodegen.SubscriptionPayData(
      quantity,
      new RelTime(60 * 60 * 1000000L),
      new RelTime(10 * 60 * 1000000L),
      new RelTime(60 * 1000000L),
    )
    (subscription, payData)
  }

  private def createSelfSubscriptionRequest(
      aliceUserParty: PartyId,
      nextPaymentDueAt: Instant,
      quantity: paymentCodegen.PaymentQuantity,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId = createSelfSubscriptionContext(aliceUserParty)
    val (subscription, payData) =
      createSelfSubscriptionData(contextId, aliceUserParty, nextPaymentDueAt, quantity)
    clue("Create subscription request") {
      val subscriptionRequest = new subsCodegen.SubscriptionRequest(
        subscription,
        payData,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = subscriptionRequest.create.commands.asScala.toSeq,
      )
    }
  }

  private def createSelfSubscription(
      aliceUserParty: PartyId,
      nextPaymentDueAt: Instant,
      quantity: paymentCodegen.PaymentQuantity = defaultSubscriptionQuantity,
  )(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val contextId = createSelfSubscriptionContext(aliceUserParty)
    val (subscriptionData, payData) =
      createSelfSubscriptionData(contextId, aliceUserParty, nextPaymentDueAt, quantity)
    val subscriptionId = clue("Create a subscription") {
      val subscription = new subsCodegen.Subscription(
        aliceUserParty.toProtoPrimitive,
        aliceUserParty.toProtoPrimitive,
        aliceUserParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = subscription.create.commands.asScala.toSeq,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.acs
        .awaitJava(subsCodegen.Subscription.COMPANION)(aliceUserParty, _.data == subscription)
        .id
    }
    clue("Create a subscription idle state") {
      val state = new subsCodegen.SubscriptionIdleState(
        subscriptionId,
        subscriptionData,
        payData,
        nextPaymentDueAt,
      )
      aliceWalletBackend.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
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
