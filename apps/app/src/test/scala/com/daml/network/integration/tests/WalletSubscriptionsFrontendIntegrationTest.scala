package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.wallet.payment as paymentCodegen
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.util.{SpliceUtil, FrontendLoginUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.{Duration, LocalDate}

class WalletSubscriptionsFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with FrontendLoginUtil
    with TimeTestUtil {

  override def walletAmuletPrice = SpliceUtil.damlDecimal(2.0)
  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAmuletPrice(walletAmuletPrice)
      // TODO(#8300) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "A wallet UI" should {

    "show and cancel subscriptions" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val alicePartyId = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceEntryName = perTestCaseName("alice")
      val dsoEntry = expectedDsoAns
      createAnsEntry(aliceAnsExternalClient, aliceEntryName, aliceWalletClient)
      val ansPaymentDue = LocalDate.now().plusDays(90)
      val aDate = LocalDate.now().plusDays(1)
      val selfSubscriptionDescription = "A recurring thing"
      createSelfSubscription(
        aliceValidatorBackend.participantClientWithAdminToken,
        aliceDamlUser,
        alicePartyId,
        paymentAmount(42.0, paymentCodegen.Unit.AMULETUNIT),
        paymentInterval = Duration.ofDays(1),
        description = selfSubscriptionDescription,
      )

      def matchSecondSubscription(row: Element) = matchSubscription(row)(
        expectedReceiver = expectedAns(alicePartyId, aliceEntryName),
        expectedProvider = expectedAns(alicePartyId, aliceEntryName),
        expectedPrice = s"42 $amuletNameAcronym per 1 day",
        expectedAmuletPrice = s"84 USD @ 0.5$amuletNameAcronym/USD",
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
              expectedReceiver = dsoEntry,
              expectedProvider = dsoEntry,
              expectedPrice = "1 USD per 90 days",
              expectedAmuletPrice = s"0.5 $amuletNameAcronym @ 2USD/$amuletNameAcronym",
              expectedPaymentDate =
                s"${ansPaymentDue.getMonthValue}/${ansPaymentDue.getDayOfMonth}/${ansPaymentDue.getYear}",
              expectedButtonEnabled = true,
              expectedDescription = s"ANS entry: \"$aliceEntryName\"",
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
      createAnsEntry(
        aliceAnsExternalClient,
        aliceEntryName1,
        aliceWalletClient,
        walletAmuletToUsd(5),
      )

      val dsoEntry = expectedDsoAns
      val ansPaymentDue = LocalDate.now().plusDays(90)
      val newlyPurchasedName = perTestCaseName("new")
      val respond =
        requestAnsEntry(aliceAnsExternalClient, newlyPurchasedName)

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
              // the PartyId copy button and parentheses are also there
              .text should include(aliceEntryName1)

            find(className("available-balance"))
              .valueOrFail("Balance is not shown")
              // from the original `createAnsEntry`
              .text should matchText(
              s"Total Available Balance: 4.4475 $amuletNameAcronym / 8.895 USD"
            )

            find(className("sub-request-description"))
              .valueOrFail("Description is not shown")
              .text should matchText(s"ANS entry: \"$newlyPurchasedName\"")

            find(className("sub-request-price"))
              .valueOrFail("Price is not shown")
              .text should matchText("1 USD per 90 days")

            find(className("sub-request-price-converted"))
              .valueOrFail("Price conversion is not shown")
              .text should matchText(s"0.5 $amuletNameAcronym @ 2 USD/$amuletNameAcronym")
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
            subscriptionRows should have size 2 // from createAnsEntry and just-accepted requestAnsEntry
            matchSubscription(subscriptionRows.last)(
              expectedReceiver = dsoEntry,
              expectedProvider = dsoEntry,
              expectedPrice = "1 USD per 90 days",
              expectedAmuletPrice = s"0.5 $amuletNameAcronym @ 2USD/$amuletNameAcronym",
              expectedPaymentDate =
                s"${ansPaymentDue.getMonthValue}/${ansPaymentDue.getDayOfMonth}/${ansPaymentDue.getYear}",
              expectedButtonEnabled = true,
              expectedDescription = s"ANS entry: \"$newlyPurchasedName\"",
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
      expectedAmuletPrice: String,
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

    subscriptionRow.childElement(className("sub-amulet-price")).text should matchText(
      expectedAmuletPrice
    )

    subscriptionRow.childElement(className("sub-payment-date")).text should matchText(
      expectedPaymentDate
    )

    subscriptionRow.childElement(className("sub-amulet-price")).text should matchText(
      expectedAmuletPrice
    )

    cancelIsEnabled(subscriptionRow, expectedButtonEnabled)
  }

  private def cancelIsEnabled(subscriptionRow: Element, expectedButtonEnabled: Boolean) = {
    subscriptionRow
      .childElement(className("sub-cancel-button"))
      .isEnabled shouldEqual expectedButtonEnabled
  }
}
