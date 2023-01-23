package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.{payment as paymentCodegen}
import com.daml.network.util.WalletTestUtil

import java.time.Instant
import java.time.temporal.ChronoUnit

class WalletSubscriptionsFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvirontment("alice")
    with WalletTestUtil {

  "A wallet UI" should {

    "show subscription requests and allow users to accept them" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting the subscription request
      requestDirectoryEntry(aliceUserParty, aliceDirectory, perTestCaseName("alice.cns"))

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
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting and financing subscriptions

      val expectedIdleText = "Waiting for next payment to become due"
      val expectedPaymentText = "Payment in progress"

      withFrontEnd("alice") { implicit webDriver =>
        clue("Create a subscription for registering alice.cns") {
          requestDirectoryEntry(aliceUserParty, aliceDirectory, perTestCaseName("alice.cns"))
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
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val expectedDirName = createDirectoryEntryForDirectoryItself
      aliceWallet.tap(50) // she'll need this for accepting and financing subscriptions

      val expectedIdleText = "Waiting for next payment to become due"

      withFrontEnd("alice") { implicit webDriver =>
        clue("Create subscription") {
          requestDirectoryEntry(aliceUserParty, aliceDirectory, perTestCaseName("alice1.cns"))
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

    "display currency in subscriptions and subscription requests" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val usdAmount = new paymentCodegen.PaymentAmount(
        BigDecimal(42.0).bigDecimal,
        paymentCodegen.Currency.USD,
      )
      val dueAt = Instant.now().plus(1, ChronoUnit.DAYS)
      createSelfSubscription(aliceUserParty, nextPaymentDueAt = dueAt, amount = usdAmount)
      createSelfSubscriptionRequest(
        aliceUserParty,
        nextPaymentDueAt = dueAt,
        amount = usdAmount,
      )
      createSelfPaymentRequest(aliceUserParty, 42, paymentCodegen.Currency.CC)
      withFrontEnd("alice") { implicit webDriver =>
        browseToSubscriptions(aliceDamlUser)
        clue("Check that the subscription request displays the currency") {
          eventually() {
            inside(findAll(className("sub-requests-table-row")).toList) { case Seq(row) =>
              row.childElement(className("sub-request-amount")).text should matchText(
                "42.0000000000USD"
              )
            }
          }
        }
        clue("Check that the subscription displays the currency") {
          eventually() {
            inside(findAll(className("subs-table-row")).toList) { case Seq(row) =>
              row.childElement(className("sub-amount")).text should matchText("42.0000000000USD")
            }
          }
        }
      }
    }
  }
}
