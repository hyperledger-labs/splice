package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.{
  FrontendLoginUtil,
  TimeTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.{Duration, LocalDate}

class WalletNewSubscriptionsFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withCoinPrice(2)

  "A wallet UI" should {

    val aliceWalletNewPort = 3007

    "show and cancel subscriptions" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val alicePartyId = setupForTestWithDirectory(aliceWallet, aliceValidator)
      val aliceEntryName = perTestCaseName("alice.cns")
      val directoryParty = createDirectoryEntryForDirectoryItself
      // TODO: (#3065) when the FE can accept subscriptions, use that instead of "auto-accepting" here
      createDirectoryEntry(alicePartyId, aliceDirectory, aliceEntryName, aliceWallet)
      val directoryPaymentDue = LocalDate.now().plusDays(90)
      val aDate = LocalDate.now().plusDays(1)
      createSelfSubscription(
        aliceWalletBackend.remoteParticipantWithAdminToken,
        aliceDamlUser,
        alicePartyId,
        paymentAmount(42.0, paymentCodegen.Currency.CC),
        paymentInterval = Duration.ofDays(1),
      )

      def matchSecondSubscription(row: Element) = matchSubscription(row)(
        expectedReceiver = expectedCns(alicePartyId, aliceEntryName),
        expectedProvider = expectedCns(alicePartyId, aliceEntryName),
        expectedPrice = "42 CC per 1 day",
        expectedCoinPrice = "84 USD @ 0.5CC/USD",
        expectedPaymentDate = s"${aDate.getMonthValue}/${aDate.getDayOfMonth}/${aDate.getYear}",
        expectedButtonEnabled = true,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToWallet(aliceWalletNewPort, aliceDamlUser)
        val (_, subscriptionRows) = actAndCheck(
          "Alice goes to the subscriptions page", {
            click on "navlink-subscriptions"
          },
        )(
          "Alice sees her subscriptions",
          _ => {
            val subscriptionRows = findAll(className("subscription-row")).toSeq
            subscriptionRows should have size 2
            matchSubscription(subscriptionRows.head)(
              expectedReceiver = directoryParty,
              expectedProvider = directoryParty,
              expectedPrice = "1 USD per 90 days",
              expectedCoinPrice = "0.5 CC @ 2USD/CC",
              expectedPaymentDate =
                s"${directoryPaymentDue.getMonthValue}/${directoryPaymentDue.getDayOfMonth}/${directoryPaymentDue.getYear}",
              expectedButtonEnabled = true,
            )
            matchSecondSubscription(subscriptionRows(1))
            subscriptionRows
          },
        )

        actAndCheck(
          "Alice cancels a subscription", {
            click on subscriptionRows.head.childElement(className("sub-cancel-button"))
          },
        )(
          "Alice sees only one subscription",
          _ => {
            val subscriptionRowsAfterCancel = findAll(className("subscription-row")).toSeq
            subscriptionRowsAfterCancel should have size 1
            matchSecondSubscription(subscriptionRowsAfterCancel.head)
          },
        )
      }
    }

    "disable cancelling non-idle transactions" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.ledgerApiUser
      val alicePartyId = setupForTestWithDirectory(aliceWallet, aliceValidator)
      aliceWallet.tap(50) // she'll need this for MakePayment to happen (but not collection)
      clue("Create subscription, the payment on which won't be collected") {
        createSelfSubscription(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceDamlUser,
          alicePartyId,
        )
      }

      withFrontEnd("alice") { implicit webDriver =>
        browseToWallet(aliceWalletNewPort, aliceDamlUser)
        actAndCheck(
          "Alice goes to the subscriptions page", {
            click on "navlink-subscriptions"
          },
        )(
          "Alice sees the subscription, but cannot cancel it",
          _ => {
            val subscriptionRows = findAll(className("subscription-row")).toSeq
            subscriptionRows should have size 1
            cancelIsEnabled(subscriptionRows.head, expectedButtonEnabled = false)
          },
        )
      }
    }

  }

  private def matchSubscription(subscriptionRow: Element)(
      expectedReceiver: String,
      expectedProvider: String,
      expectedPrice: String,
      expectedCoinPrice: String,
      expectedPaymentDate: String,
      expectedButtonEnabled: Boolean,
      expectedDescription: String = "Service Desc.", // TODO: (#3304) check the description
  ) = {
    subscriptionRow.childElement(className("sub-receiver")).text should matchText(expectedReceiver)

    subscriptionRow.childElement(className("sub-provider")).text should matchText(expectedProvider)

    subscriptionRow.childElement(className("sub-description")).text should matchText(
      expectedDescription
    )

    subscriptionRow.childElement(className("sub-price")).text should matchText(expectedPrice)

    subscriptionRow.childElement(className("sub-coin-price")).text should matchText(
      expectedCoinPrice
    )

    subscriptionRow.childElement(className("sub-payment-date")).text should matchText(
      expectedPaymentDate
    )

    subscriptionRow.childElement(className("sub-coin-price")).text should matchText(
      expectedCoinPrice
    )

    cancelIsEnabled(subscriptionRow, expectedButtonEnabled)
  }

  private def cancelIsEnabled(subscriptionRow: Element, expectedButtonEnabled: Boolean) = {
    subscriptionRow
      .childElement(className("sub-cancel-button"))
      .isEnabled shouldEqual expectedButtonEnabled
  }
}
