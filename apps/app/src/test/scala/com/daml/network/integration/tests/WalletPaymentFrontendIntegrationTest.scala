package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.codegen.java.cn.wallet.payment.{AcceptedAppPayment, Currency}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{Contract, FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

class WalletPaymentFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  private val coinPrice = 2
  private val tolerance = 0.005

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withCoinPrice(coinPrice)

  "A wallet payments UI" should {

    "for single receiver" should {

      "allow accepting payments in CC" in { implicit env =>
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        val aliceEntryName = perTestCaseName("alice.cns")
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)

        val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
        val charlieEntryName = perTestCaseName("charlie.cns")
        createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

        val description = "this will be accepted (in CC)"

        val (_, paymentRequestContractId, _) = createPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, BigDecimal("1.5"), paymentCodegen.Currency.CC)
          ),
          description = description,
        )

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice goes to the page to confirm the payment", {
              go to s"http://localhost:3000/confirm-payment/${paymentRequestContractId.contractId}"
              loginOnCurrentPage(3000, aliceDamlUser)
            },
          )(
            "Alice sees the payment information",
            _ => {
              matchSinglePaymentInfo(id("confirm-payment").element)(
                expectedBalance =
                  "Total Available Balance: 4.4475 CC / 8.895 USD", // from the self-directory creation
                expectedSendAmount = "1.5" -> Currency.CC,
                expectedReceiver = expectedCns(charlieUserParty, charlieEntryName),
                expectedProvider = expectedCns(aliceUserParty, aliceEntryName),
                expectedTotalCC = "1.5",
                expectedComputeText = "3 USD @ 0.5 CC/USD",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidator.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment.contractId,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-1.5"),
                provider = expectedCns(aliceUserParty, aliceEntryName),
                charlieUserParty,
                charlieEntryName,
                BigDecimal("0"),
              )
            },
          )

        }
      }

      "allow accepting payments in USD" in { implicit env =>
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        val aliceEntryName = perTestCaseName("alice.cns")
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)

        val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
        val charlieEntryName = perTestCaseName("charlie.cns")
        createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

        val description = "this will be accepted (in USD)"

        val (_, paymentRequestContractId, _) = createPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, BigDecimal("5.5"), paymentCodegen.Currency.USD)
          ),
          description = description,
        )

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice goes to the page to confirm the payment", {
              go to s"http://localhost:3000/confirm-payment/${paymentRequestContractId.contractId}"
              loginOnCurrentPage(3000, aliceDamlUser)
            },
          )(
            "Alice sees the payment information",
            _ => {
              matchSinglePaymentInfo(id("confirm-payment").element)(
                expectedBalance =
                  "Total Available Balance: 4.4475 CC / 8.895 USD", // from the self-directory creation
                expectedSendAmount = "5.5" -> Currency.USD,
                expectedReceiver = expectedCns(charlieUserParty, charlieEntryName),
                expectedProvider = expectedCns(aliceUserParty, aliceEntryName),
                expectedTotalCC = "2.75",
                expectedComputeText = "5.5 USD @ 2 USD/CC",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidator.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment.contractId,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-2.75"),
                provider = expectedCns(aliceUserParty, aliceEntryName),
                charlieUserParty,
                charlieEntryName,
                BigDecimal("0"),
              )
            },
          )

        }
      }

    }

    "for multiple receivers" should {

      "allow accepting payments in CC" in { implicit env =>
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        val aliceEntryName = perTestCaseName("alice.cns")
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)

        val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
        val charlieEntryName = perTestCaseName("charlie.cns")
        createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

        val description = "this will be accepted (in CC)"

        val (_, paymentRequestContractId, _) = createPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(aliceUserParty, BigDecimal("1.5"), paymentCodegen.Currency.CC),
            receiverAmount(charlieUserParty, BigDecimal("2.5"), paymentCodegen.Currency.CC),
          ),
          description = description,
        )

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice goes to the page to confirm the payment", {
              go to s"http://localhost:3000/confirm-payment/${paymentRequestContractId.contractId}"
              loginOnCurrentPage(3000, aliceDamlUser)
            },
          )(
            "Alice sees the payment information",
            _ => {
              matchMultipleRecipientPaymentInfo(id("confirm-payment").element)(
                expectedBalance =
                  "Total Available Balance: 4.4475 CC / 8.895 USD", // from the self-directory creation
                expectedReceivers = Seq(
                  (aliceUserParty, aliceEntryName, "1.5 CC", "3 USD"),
                  (charlieUserParty, charlieEntryName, "2.5 CC", "5 USD"),
                ),
                expectedProvider = expectedCns(aliceUserParty, aliceEntryName),
                expectedTotalCC = "4",
                expectedComputeText = "8 USD @ 0.5 CC/USD",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidator.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment.contractId,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-4"),
                provider = expectedCns(aliceUserParty, aliceEntryName),
                charlieUserParty,
                charlieEntryName,
                BigDecimal("1.5"), // that's what Alice receives
              )
            },
          )

        }
      }

      "allow accepting payments in USD" in { implicit env =>
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        val aliceEntryName = perTestCaseName("alice.cns")
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)

        val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
        val charlieEntryName = perTestCaseName("charlie.cns")
        createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

        val description = "this will be accepted (in USD)"

        val (_, paymentRequestContractId, _) = createPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(aliceUserParty, BigDecimal("1.5"), paymentCodegen.Currency.USD),
            receiverAmount(charlieUserParty, BigDecimal("2.5"), paymentCodegen.Currency.USD),
          ),
          description = description,
        )

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice goes to the page to confirm the payment", {
              go to s"http://localhost:3000/confirm-payment/${paymentRequestContractId.contractId}"
              loginOnCurrentPage(3000, aliceDamlUser)
            },
          )(
            "Alice sees the payment information",
            _ => {
              matchMultipleRecipientPaymentInfo(id("confirm-payment").element)(
                expectedBalance =
                  "Total Available Balance: 4.4475 CC / 8.895 USD", // from the self-directory creation
                expectedReceivers = Seq(
                  (aliceUserParty, aliceEntryName, "1.5 USD", "0.75 CC"),
                  (charlieUserParty, charlieEntryName, "2.5 USD", "1.25 CC"),
                ),
                expectedProvider = expectedCns(aliceUserParty, aliceEntryName),
                expectedTotalCC = "2",
                expectedComputeText = "4 USD @ 2 USD/CC",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidator.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment.contractId,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-2"),
                provider = expectedCns(aliceUserParty, aliceEntryName),
                charlieUserParty,
                charlieEntryName,
                BigDecimal("0.75"), // that's what Alice receives
              )
            },
          )

        }
      }

      "allow accepting payments in both CC & USD" in { implicit env =>
        val aliceDamlUser = aliceWallet.config.ledgerApiUser
        val aliceUserParty = setupForTestWithDirectory(aliceWallet, aliceValidator)
        val aliceEntryName = perTestCaseName("alice.cns")
        createDirectoryEntry(aliceUserParty, aliceDirectory, aliceEntryName, aliceWallet)

        val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
        val charlieEntryName = perTestCaseName("charlie.cns")
        createDirectoryEntry(charlieUserParty, charlieDirectory, charlieEntryName, charlieWallet)

        val description = "this will be accepted (in USD)"

        val (_, paymentRequestContractId, _) = createPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(aliceUserParty, BigDecimal("1.5"), paymentCodegen.Currency.CC),
            receiverAmount(charlieUserParty, BigDecimal("2.5"), paymentCodegen.Currency.USD),
          ),
          description = description,
        )

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice goes to the page to confirm the payment", {
              go to s"http://localhost:3000/confirm-payment/${paymentRequestContractId.contractId}"
              loginOnCurrentPage(3000, aliceDamlUser)
            },
          )(
            "Alice sees the payment information",
            _ => {
              matchMultipleRecipientPaymentInfo(id("confirm-payment").element)(
                expectedBalance =
                  "Total Available Balance: 4.4475 CC / 8.895 USD", // from the self-directory creation
                expectedReceivers = Seq(
                  (aliceUserParty, aliceEntryName, "1.5 CC", "3 USD"),
                  (charlieUserParty, charlieEntryName, "2.5 USD", "1.25 CC"),
                ),
                expectedProvider = expectedCns(aliceUserParty, aliceEntryName),
                expectedTotalCC = "2.75",
                expectedComputeText = "5.5 USD @ 0.5 CC/USD",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidator.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment.contractId,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-2.75"),
                provider = expectedCns(aliceUserParty, aliceEntryName),
                charlieUserParty,
                charlieEntryName,
                BigDecimal("1.5"), // that's what Alice receives
              )
            },
          )

        }
      }

    }

  }

  private def confirmPayment()(implicit
      env: CNNodeTestConsoleEnvironment,
      webDriverType: WebDriverType,
  ): Contract[AcceptedAppPayment.ContractId, AcceptedAppPayment] = {
    actAndCheck(
      "Alice clicks on the button to confirm the payment", {
        click on className("payment-accept")
        go to s"http://localhost:3000"
      },
    )(
      "The payment is processed",
      _ => {
        aliceWallet.listAppPaymentRequests() shouldBe empty
        val acceptedPayments = aliceWallet.listAcceptedAppPayments()
        acceptedPayments should have size 1
        acceptedPayments.head
      },
    )._2
  }

  private def matchSinglePaymentInfo(element: Element)(
      expectedBalance: String,
      expectedSendAmount: (String, Currency),
      expectedReceiver: String,
      expectedProvider: String,
      expectedTotalCC: String,
      expectedComputeText: String,
      expectedDescription: String,
  ) = {
    matchPaymentCommon(element)(
      expectedBalance,
      expectedProvider,
      expectedTotalCC,
      expectedComputeText,
      expectedDescription,
    )

    element.childElement(className("payment-amount")).text should matchText(
      s"Send ${expectedSendAmount._1} ${expectedSendAmount._2} to"
    )

    element.childElement(className("payment-receiver")).text should matchText(expectedReceiver)
  }

  private def matchMultipleRecipientPaymentInfo(element: Element)(
      expectedBalance: String,
      expectedReceivers: Seq[(PartyId, String, String, String)],
      expectedProvider: String,
      expectedTotalCC: String,
      expectedComputeText: String,
      expectedDescription: String,
  ): Unit = {
    matchPaymentCommon(element)(
      expectedBalance,
      expectedProvider,
      expectedTotalCC,
      expectedComputeText,
      expectedDescription,
    )

    expectedReceivers.foreach {
      case (partyId, expectedReceiver, expectedAmount, expectedConvertedAmount) =>
        val row = element.childElement(id(s"${partyId.toProtoPrimitive}-payment-row"))
        row.childElement(className("receiver-entry")).text should matchText(
          expectedCns(partyId, expectedReceiver)
        )
        row.childElement(className("receiver-amount")).text should matchText(expectedAmount)
        row.childElement(className("receiver-amount-converted")).text should matchText(
          expectedConvertedAmount
        )
    }
  }

  private def matchPaymentCommon(element: Element)(
      expectedBalance: String,
      expectedProvider: String,
      expectedTotalCC: String,
      expectedComputeText: String,
      expectedDescription: String,
  ) = {
    element.childElement(className("available-balance")).text should matchTextMixedWithNumbers(
      expectedBalance,
      tolerance,
    )

    element.childElement(className("payment-provider")).text should matchText(expectedProvider)

    // TODO (#3492): test with fee
    element.childElement(className("payment-total-cc")).text should matchTextMixedWithNumbers(
      s"$expectedTotalCC CC",
      tolerance,
    )

    element.childElement(className("payment-compute")).text should matchText(expectedComputeText)

    element.childElement(className("payment-description")).text should matchText(
      s"\"$expectedDescription\""
    )
  }

  /** Matches the two parts of an app payment where the currently logged user
    * pays to `expectedPartyId` via the given app `provider`:
    * the first entry is the sender locking a coin,
    * the second entry is the receiver unlocking the coin and transferring to themselves
    * the part of the transfer that belongs to them.
    *
    * @see https://github.com/DACH-NY/canton-network-node/pull/3787#discussion_r1153496149
    */
  private def matchLockAndTransfer(
      expectedLockedAmount: BigDecimal,
      provider: String,
      receiverPartyId: PartyId,
      expectedEntryName: String,
      balanceChangeForSender: BigDecimal,
  )(implicit driver: WebDriverType, env: CNNodeTestConsoleEnvironment) = {
    inside(findAll(className("tx-row")).toList) { case paymentTx :: lockTx :: _ =>
      matchTransaction(lockTx)(
        coinPrice,
        "Sent",
        UserWalletTxLogParser.TxLogEntry.Transfer.AppPaymentAccepted,
        Some(s"Automation via ${aliceValidator.getValidatorPartyId().toProtoPrimitive}"),
        expectedLockedAmount,
      )

      matchTransaction(paymentTx)(
        coinPrice,
        "Sent",
        UserWalletTxLogParser.TxLogEntry.Transfer.AppPaymentCollected,
        Some(s"${expectedCns(receiverPartyId, expectedEntryName)} via $provider"),
        balanceChangeForSender,
      )
    }
  }

}
