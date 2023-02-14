package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}
import org.openqa.selenium.{Keys, WebDriver}

class WalletPaymentFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "bob")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  "A wallet UI" should {

    "show app payment requests and allow users to reject them" in { implicit env =>
      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      createSelfPaymentRequest(aliceUserParty, 42, paymentCodegen.Currency.CC)

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)

        clue("Payment request is listed") {
          eventually() {
            findAll(className("app-requests-table-row")).toList.length shouldBe 1
          }
        }
        actAndCheck("Clicking the \"Reject\" button", click on className("reject-button"))(
          "No more payment requests are listed",
          // this can't have been due to an "accept" because Alice doesn't have enough funds for that
          _ => findAll(className("app-requests-table-row")).toList shouldBe empty,
        )
      }
    }

    "show app payment requests in CC, and correctly handle unresolved party IDs" in {
      implicit env =>
        // Alice submits a directory entry request, which will create an app payment request in her wallet
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        createSelfPaymentRequest(aliceUserParty, 42, paymentCodegen.Currency.CC)

        withFrontEnd("alice") { implicit webDriver =>
          browseToPaymentRequests(aliceDamlUser)

          verifyRequestAmountIsDisplayed(42)

          // Verify that the receiver table row is properly displayed
          eventually() {
            inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
              // Check that alice party ID could not be resolved against the directory
              // service, and the party ID is shown instead.
              row
                .childElement(className("app-request-receiver"))
                .text shouldBe aliceUserParty.toProtoPrimitive

              // Verify that the currency and amount are properly displayed
              verifyReceiverTableAmounts("42.0CC")
            }
          }
        }
    }

    "show app payment requests in USD (with USD==CC), and correctly handle unresolved party IDs" in {
      implicit env =>
        // Alice submits a directory entry request, which will create an app payment request in her wallet
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        createSelfPaymentRequest(aliceUserParty, 42, paymentCodegen.Currency.USD)

        withFrontEnd("alice") { implicit webDriver =>
          browseToPaymentRequests(aliceDamlUser)

          verifyRequestAmountIsDisplayed(42)

          // Verify that the receiver table row is properly displayed
          eventually() {
            inside(findAll(className("app-request-breakdown-table-row")).toList) { case Seq(row) =>
              // Check that alice party ID could not be resolved against the directory
              // service, and the party ID is shown instead.
              row
                .childElement(className("app-request-receiver"))
                .text shouldBe aliceUserParty.toProtoPrimitive

              // Verify that the currency and amount are properly displayed
              verifyReceiverTableAmounts("42.0USD")
            }
          }
        }
    }

    "show app payment requests with multiple receivers (CC only)" in { implicit env =>
      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)

      createPaymentRequest(
        aliceUserParty,
        Seq(
          receiverAmount(aliceUserParty, 22, paymentCodegen.Currency.CC),
          receiverAmount(aliceUserParty, 20, paymentCodegen.Currency.CC),
        ),
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToPaymentRequests(aliceDamlUser)

        verifyRequestAmountIsDisplayed(42)

        // Verify that the receiver table rows contain both receiver amounts
        verifyReceiverTableAmounts("22.0CC", "20.0CC")
      }
    }

    "show app payment requests, and resolve party IDs in them" in { implicit env =>
      // Alice submits a directory entry request, which will create an app payment request in her wallet
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceDirectoryName = perTestCaseName("alice.cns")
      val aliceDirectoryDisplay = expectedCns(aliceUserParty, aliceDirectoryName)
      createDirectoryEntry(aliceUserParty, aliceDirectory, aliceDirectoryName, aliceWallet)
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

    "support different ways of defining the receiver in transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = perTestCaseName("alice.cns")
      actAndCheck("Tap coin for alice", aliceWallet.tap(50))(
        "Alice has coin",
        _ => (aliceWallet.list().coins.length shouldBe 1),
      )
      createDirectoryEntry(aliceParty, aliceDirectory, aliceEntryName, aliceWallet)
      val bobParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobEntryName = perTestCaseName("bob.cns")
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
            setDirectoryField(
              textField("create-offer-receiver"),
              bobEntryName,
              bobParty.toProtoPrimitive,
            )
            click on "create-offer-amount"
            numberField("create-offer-amount").underlying.sendKeys("1.0")
            click on "create-offer-description"
            textField("create-offer-description").value = "by party ID"
            click on "create-offer-expiration-value"
            numberField("create-offer-expiration-value").underlying.sendKeys("120")
            click on "submit-create-offer-button"
          },
        )(
          "Alice sees the transfer offer",
          _ => {
            findAll(className("transfer-offers-row")) should have size 1
            val row = inside(findAll(className("transfer-offers-row")).toList) { case Seq(row) =>
              row
            }
            row.childElement(className("transfer-offers-table-amount")).text should be(
              "1.0CC"
            )
          },
        )

        actAndCheck(
          s"Alice creates offer with auto-complete", {
            click on "create-offer-button"
            click on "create-offer-receiver"
            textField("create-offer-receiver").value = "b"
            textField("create-offer-receiver").underlying.sendKeys(Keys.ARROW_DOWN)
            textField("create-offer-receiver").underlying.sendKeys(Keys.RETURN)
            waitForDirectoryField(textField("create-offer-receiver"), bobParty.toProtoPrimitive)
            click on "create-offer-amount"
            numberField("create-offer-amount").underlying.sendKeys("2.0")
            click on "create-offer-description"
            textField("create-offer-description").value = "with auto-complete"
            click on "create-offer-expiration-value"
            numberField("create-offer-expiration-value").underlying.sendKeys("120")
            click on "submit-create-offer-button"
          },
        )(
          "Alice sees the transfer offer",
          _ => {
            findAll(className("transfer-offers-row")) should have size 2
          },
        )

        actAndCheck(
          s"Alice creates offer with bob's full party ID", {
            click on "create-offer-button"
            click on "create-offer-receiver"
            setDirectoryField(
              textField("create-offer-receiver"),
              bobParty.toProtoPrimitive,
              bobParty.toProtoPrimitive,
            )
            click on "create-offer-amount"
            numberField("create-offer-amount").underlying.sendKeys("3.0")
            click on "create-offer-description"
            textField("create-offer-description").value = "with auto-complete"
            click on "create-offer-expiration-value"
            numberField("create-offer-expiration-value").underlying.sendKeys("120")
            click on "submit-create-offer-button"
          },
        )(
          "Alice sees the transfer offers",
          _ => {
            findAll(className("transfer-offers-row")) should have size 3
          },
        )

        clue("Bob sees all offers") {
          eventually() {
            bobWallet.listTransferOffers() should have length 3
          }
        }
      }
    }

    "support withdrawing and rejecting transfer offers" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = perTestCaseName("alice.cns")
      actAndCheck("Tap coin for alice", aliceWallet.tap(50))(
        "Alice has coin",
        _ => (aliceWallet.list().coins.length shouldBe 1),
      )
      createDirectoryEntry(aliceParty, aliceDirectory, aliceEntryName, aliceWallet)
      val bobDamlUser = bobWallet.config.ledgerApiUser
      val bobParty = setupForTestWithDirectory(bobWallet, bobValidator)
      val bobEntryName = perTestCaseName("bob.cns")
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
            setDirectoryField(
              textField("create-offer-receiver"),
              bobParty.toProtoPrimitive,
              bobParty.toProtoPrimitive,
            )
            click on "create-offer-amount"
            numberField("create-offer-amount").underlying.sendKeys("100.0")
            click on "create-offer-description"
            textField("create-offer-description").value = description
            click on "create-offer-expiration-value"
            numberField("create-offer-expiration-value").underlying.sendKeys("120")
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
        createOffer("to be withdrawn")
        actAndCheck(
          "Alice withdraws her offer", {
            click on className("transfer-offers-table-withdraw")
          },
        )("The offer is deleted", _ => findAll(className("transfer-offers-row")) should have size 0)

        createOffer("to be rejected")
      }

      withFrontEnd("bob") { implicit webDrivers =>
        browseToBobWallet(bobDamlUser)
        click on "transfer-offers-button"
        eventually()(findAll(className("transfer-offers-row")) should have size 1)
        actAndCheck("Bob rejects the offer", click on className("transfer-offers-table-reject"))(
          "The offer is deleted",
          _ => findAll(className("transfer-offers-row")) should have size 0,
        )
      }

    // Testing a short-lived offer here might be racy, so for now we're not doing that. The different units of time
    // have been manually tested instead, and general expiration times is tested in the non-frontend integration test.

    // TODO(#1799): Currently it is hard to test the scenario of insufficient funds,
    //  until we have some form of notification that the offer has been aborted
    }
  }
}
