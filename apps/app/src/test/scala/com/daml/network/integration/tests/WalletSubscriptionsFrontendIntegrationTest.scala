package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.{Duration, LocalDate}

class WalletSubscriptionsFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with FrontendLoginUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withCoinPrice(2)
      // TODO(#8300) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "A wallet UI" should {

    "show and cancel subscriptions" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val alicePartyId = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceEntryName = perTestCaseName("alice")
      val cnsParty = createCnsEntryForItself
      createCnsEntry(aliceCnsExternalClient, aliceEntryName, aliceWalletClient)
      val cnsPaymentDue = LocalDate.now().plusDays(90)
      val aDate = LocalDate.now().plusDays(1)
      val selfSubscriptionDescription = "A recurring thing"
      createSelfSubscription(
        aliceValidatorBackend.participantClientWithAdminToken,
        aliceDamlUser,
        alicePartyId,
        paymentAmount(42.0, paymentCodegen.Currency.CC),
        paymentInterval = Duration.ofDays(1),
        description = selfSubscriptionDescription,
      )

      def matchSecondSubscription(row: Element) = matchSubscription(row)(
        expectedReceiver = expectedCns(alicePartyId, aliceEntryName),
        expectedProvider = expectedCns(alicePartyId, aliceEntryName),
        expectedPrice = "42 CC per 1 day",
        expectedCoinPrice = "84 USD @ 0.5CC/USD",
        expectedPaymentDate = s"${aDate.getMonthValue}/${aDate.getDayOfMonth}/${aDate.getYear}",
        expectedButtonEnabled = true,
        expectedDescription = selfSubscriptionDescription,
      )

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
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
              expectedReceiver = cnsParty,
              expectedProvider = cnsParty,
              expectedPrice = "1 USD per 90 days",
              expectedCoinPrice = "0.5 CC @ 2USD/CC",
              expectedPaymentDate =
                s"${cnsPaymentDue.getMonthValue}/${cnsPaymentDue.getDayOfMonth}/${cnsPaymentDue.getYear}",
              expectedButtonEnabled = true,
              expectedDescription = s"CNS entry: \"$aliceEntryName\"",
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
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val alicePartyId = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(50) // she'll need this for MakePayment to happen (but not collection)
      clue("Create subscription, the payment on which won't be collected") {
        createSelfSubscription(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceDamlUser,
          alicePartyId,
        )
      }

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
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

    "allow accepting subscriptions" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceEntryName1 = perTestCaseName("alice")
      createCnsEntry(aliceCnsExternalClient, aliceEntryName1, aliceWalletClient)

      val cnsParty = createCnsEntryForItself
      val cnsPaymentDue = LocalDate.now().plusDays(90)
      val newlyPurchasedName = perTestCaseName("new")
      val respond =
        requestCnsEntry(aliceCnsExternalClient, newlyPurchasedName)

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice goes to the confirm-subscription page", {
            go to s"http://localhost:3000/confirm-subscription/${respond.subscriptionRequestCid.contractId}"
            loginOnCurrentPage(3000, aliceDamlUser)
          },
        )(
          "She sees the data of the subscription request",
          _ => {
            find(className("payment-current-user"))
              .valueOrFail("Current user is not shown")
              .text should matchText(aliceEntryName1)

            find(className("available-balance"))
              .valueOrFail("Balance is not shown")
              // from the original `createCnsEntry`
              .text should matchText("Total Available Balance: 4.4475 CC / 8.895 USD")

            find(className("sub-request-description"))
              .valueOrFail("Description is not shown")
              .text should matchText(s"CNS entry: \"$newlyPurchasedName\"")

            find(className("sub-request-price"))
              .valueOrFail("Price is not shown")
              .text should matchText("1 USD per 90 days")

            find(className("sub-request-price-converted"))
              .valueOrFail("Price conversion is not shown")
              .text should matchText("0.5 CC @ 2 USD/CC")
          },
        )

        clue("Alice accepts the subscription") {
          click on className("sub-request-accept-button")
        }

        actAndCheck(
          "Alice sees the subscription in the list", {
            go to s"http://localhost:3000" // already logged in
            click on "navlink-subscriptions"
          },
        )(
          "Alice sees the new subscription in the list",
          _ => {
            val subscriptionRows = findAll(className("subscription-row")).toSeq
            subscriptionRows should have size 2 // from createCnsEntry and just-accepted requestCnsEntry
            matchSubscription(subscriptionRows.last)(
              expectedReceiver = cnsParty,
              expectedProvider = cnsParty,
              expectedPrice = "1 USD per 90 days",
              expectedCoinPrice = "0.5 CC @ 2USD/CC",
              expectedPaymentDate =
                s"${cnsPaymentDue.getMonthValue}/${cnsPaymentDue.getDayOfMonth}/${cnsPaymentDue.getYear}",
              expectedButtonEnabled = true,
              expectedDescription = s"CNS entry: \"$newlyPurchasedName\"",
            )
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
      expectedDescription: String,
  ) = {
    val receiver = subscriptionRow.childElement(className("sub-receiver"))
    seleniumText(receiver) shouldBe expectedReceiver

    val provider = subscriptionRow.childElement(className("sub-provider"))
    seleniumText(provider) shouldBe expectedProvider

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
