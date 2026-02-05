package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as paymentCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.AcceptedAppPayment
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{
  ContractWithState,
  SpliceUtil,
  FrontendLoginUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.topology.PartyId

class WalletPaymentFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  private val amuletPrice = 2
  private val tolerance = 0.005
  override def walletAmuletPrice = SpliceUtil.damlDecimal(amuletPrice.toDouble)

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .withAmuletPrice(amuletPrice)
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "A wallet payments UI" should {

    "for single receiver" should {

      "allow accepting payments in Amulet unit" in { implicit env =>
        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceEntryName = perTestCaseName("alice")
        createAnsEntry(
          aliceAnsExternalClient,
          aliceEntryName,
          aliceWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
        val charlieEntryName = perTestCaseName("charlie")
        createAnsEntry(
          charlieAnsExternalClient,
          charlieEntryName,
          charlieWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val description = s"this will be accepted (in $amuletNameAcronym)"

        val (paymentRequestContractId, _) = createPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, BigDecimal("1.5"), paymentCodegen.Unit.AMULETUNIT)
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
                expectedBalance = (4.4475, 8.895), // from the self-directory creation
                expectedSendAmount = "1.5" -> paymentCodegen.Unit.AMULETUNIT,
                expectedReceiver = expectedAns(charlieUserParty, charlieEntryName),
                expectedProvider = expectedAns(aliceUserParty, aliceEntryName),
                expectedTotalCC = 1.5,
                expectedComputeText = s"3 USD @ 0.5 $amuletNameAcronym/USD",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidatorBackend.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-1.5"),
                charlieUserParty,
                charlieEntryName,
                BigDecimal("0"),
              )
            },
          )

        }
      }

      "allow accepting payments in USD" in { implicit env =>
        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceEntryName = perTestCaseName("alice")
        createAnsEntry(
          aliceAnsExternalClient,
          aliceEntryName,
          aliceWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
        val charlieEntryName = perTestCaseName("charlie")
        createAnsEntry(
          charlieAnsExternalClient,
          charlieEntryName,
          charlieWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val description = "this will be accepted (in USD)"

        val (paymentRequestContractId, _) = createPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, BigDecimal("5.5"), paymentCodegen.Unit.USDUNIT)
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
                expectedBalance = (4.4475, 8.895), // from the self-directory creation
                expectedSendAmount = "5.5" -> paymentCodegen.Unit.USDUNIT,
                expectedReceiver = expectedAns(charlieUserParty, charlieEntryName),
                expectedProvider = expectedAns(aliceUserParty, aliceEntryName),
                expectedTotalCC = 2.75,
                expectedComputeText = s"5.5 USD @ 2 USD/$amuletNameAcronym",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidatorBackend.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-2.75"),
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

      "allow accepting payments in Amulet unit" in { implicit env =>
        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceEntryName = perTestCaseName("alice")
        createAnsEntry(
          aliceAnsExternalClient,
          aliceEntryName,
          aliceWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
        val charlieEntryName = perTestCaseName("charlie")
        createAnsEntry(
          charlieAnsExternalClient,
          charlieEntryName,
          charlieWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val description = s"this will be accepted (in $amuletNameAcronym)"

        val (paymentRequestContractId, _) = createPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(aliceUserParty, BigDecimal("1.5"), paymentCodegen.Unit.AMULETUNIT),
            receiverAmount(charlieUserParty, BigDecimal("2.5"), paymentCodegen.Unit.AMULETUNIT),
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
                expectedBalance = (4.4475, 8.895), // from the self-directory creation
                expectedReceivers = Seq(
                  (aliceUserParty, aliceEntryName, s"1.5 $amuletNameAcronym", "3 USD"),
                  (charlieUserParty, charlieEntryName, s"2.5 $amuletNameAcronym", "5 USD"),
                ),
                expectedProvider = expectedAns(aliceUserParty, aliceEntryName),
                expectedTotalCC = 4.0,
                expectedComputeText = s"8 USD @ 0.5 $amuletNameAcronym/USD",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidatorBackend.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-4"),
                charlieUserParty,
                charlieEntryName,
                BigDecimal("1.5"), // that's what Alice receives
              )
            },
          )

        }
      }

      "allow accepting payments in USD" in { implicit env =>
        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceEntryName = perTestCaseName("alice")
        createAnsEntry(
          aliceAnsExternalClient,
          aliceEntryName,
          aliceWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
        val charlieEntryName = perTestCaseName("charlie")
        createAnsEntry(
          charlieAnsExternalClient,
          charlieEntryName,
          charlieWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val description = "this will be accepted (in USD)"

        val (paymentRequestContractId, _) = createPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(aliceUserParty, BigDecimal("1.5"), paymentCodegen.Unit.USDUNIT),
            receiverAmount(charlieUserParty, BigDecimal("2.5"), paymentCodegen.Unit.USDUNIT),
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
                expectedBalance = (4.4475, 8.895), // from the self-directory creation
                expectedReceivers = Seq(
                  (aliceUserParty, aliceEntryName, "1.5 USD", s"0.75 $amuletNameAcronym"),
                  (charlieUserParty, charlieEntryName, "2.5 USD", s"1.25 $amuletNameAcronym"),
                ),
                expectedProvider = expectedAns(aliceUserParty, aliceEntryName),
                expectedTotalCC = 2.0,
                expectedComputeText = s"4 USD @ 2 USD/$amuletNameAcronym",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidatorBackend.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-2"),
                charlieUserParty,
                charlieEntryName,
                BigDecimal("0.75"), // that's what Alice receives
              )
            },
          )

        }
      }

      "allow accepting payments in both Amulet unit & USD" in { implicit env =>
        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceEntryName = perTestCaseName("alice")
        createAnsEntry(
          aliceAnsExternalClient,
          aliceEntryName,
          aliceWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
        val charlieEntryName = perTestCaseName("charlie")
        createAnsEntry(
          charlieAnsExternalClient,
          charlieEntryName,
          charlieWalletClient,
          tapAmount = 5 * amuletPrice,
        )

        val description = "this will be accepted (in USD)"

        val (paymentRequestContractId, _) = createPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(aliceUserParty, BigDecimal("1.5"), paymentCodegen.Unit.AMULETUNIT),
            receiverAmount(charlieUserParty, BigDecimal("2.5"), paymentCodegen.Unit.USDUNIT),
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
                expectedBalance = (4.4475, 8.895), // from the self-directory creation
                expectedReceivers = Seq(
                  (aliceUserParty, aliceEntryName, s"1.5 $amuletNameAcronym", "3 USD"),
                  (charlieUserParty, charlieEntryName, "2.5 USD", s"1.25 $amuletNameAcronym"),
                ),
                expectedProvider = expectedAns(aliceUserParty, aliceEntryName),
                expectedTotalCC = 2.75,
                expectedComputeText = s"5.5 USD @ 0.5 $amuletNameAcronym/USD",
                expectedDescription = description,
              )
            },
          )

          val acceptedPayment = confirmPayment()

          actAndCheck(
            "The payment is collected", {
              collectAcceptedAppPaymentRequest(
                aliceValidatorBackend.participantClientWithAdminToken,
                aliceDamlUser,
                Seq(aliceUserParty, charlieUserParty),
                acceptedPayment,
              )
              go to s"http://localhost:3000"
            },
          )(
            "The payment is shown on Alice's transactions",
            _ => {
              matchLockAndTransfer(
                expectedLockedAmount = BigDecimal("-2.75"),
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
      env: SpliceTestConsoleEnvironment,
      webDriverType: WebDriverType,
  ): ContractWithState[AcceptedAppPayment.ContractId, AcceptedAppPayment] = {
    actAndCheck(
      "Alice clicks on the button to confirm the payment", {
        eventuallyClickOn(className("payment-accept"))
        go to s"http://localhost:3000"
      },
    )(
      "The payment is processed",
      _ => {
        aliceWalletClient.listAppPaymentRequests() shouldBe empty
        val acceptedPayments = aliceWalletClient.listAcceptedAppPayments()
        acceptedPayments should have size 1
        acceptedPayments.head
      },
    )._2
  }

  private def renderPaymentUnit(
      unit: paymentCodegen.Unit
  )(implicit env: SpliceTestConsoleEnvironment): String =
    unit match {
      case paymentCodegen.Unit.AMULETUNIT => amuletNameAcronym
      case paymentCodegen.Unit.USDUNIT => "USD"
      case _ => fail(s"Invalid unit: $unit")
    }

  private def matchSinglePaymentInfo(element: Element)(
      expectedBalance: (BigDecimal, BigDecimal),
      expectedSendAmount: (String, paymentCodegen.Unit),
      expectedReceiver: String,
      expectedProvider: String,
      expectedTotalCC: BigDecimal,
      expectedComputeText: String,
      expectedDescription: String,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    matchPaymentCommon(element)(
      expectedBalance,
      expectedProvider,
      expectedTotalCC,
      expectedComputeText,
      expectedDescription,
    )

    element.childElement(className("payment-amount")).text should matchText(
      s"Send ${expectedSendAmount._1} ${renderPaymentUnit(expectedSendAmount._2)} to"
    )

    seleniumText(element.childElement(className("payment-receiver"))) should matchText(
      expectedReceiver
    )
  }

  private def matchMultipleRecipientPaymentInfo(element: Element)(
      expectedBalance: (BigDecimal, BigDecimal),
      expectedReceivers: Seq[(PartyId, String, String, String)],
      expectedProvider: String,
      expectedTotalCC: BigDecimal,
      expectedComputeText: String,
      expectedDescription: String,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
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
        seleniumText(row.childElement(className("receiver-entry"))) should matchText(
          expectedAns(partyId, expectedReceiver)
        )
        row.childElement(className("receiver-amount")).text should matchText(expectedAmount)
        row.childElement(className("receiver-amount-converted")).text should matchText(
          expectedConvertedAmount
        )
    }
  }

  private def matchPaymentCommon(element: Element)(
      expectedBalance: (BigDecimal, BigDecimal),
      expectedProvider: String,
      expectedTotalCC: BigDecimal,
      expectedComputeText: String,
      expectedDescription: String,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    element.childElement(className("available-balance")).text should matchTextMixedWithNumbers(
      raw"Total Available Balance: ([0-9.,]+) $amuletNameAcronym / ([0-9.,]+) USD".r,
      Seq(expectedBalance._1, expectedBalance._2),
      tolerance,
    )

    seleniumText(element.childElement(className("payment-provider"))) should matchText(
      expectedProvider
    )

    // TODO (#878): test with fee
    element.childElement(className("payment-total-amulet")).text should matchTextMixedWithNumbers(
      raw"([0-9.,]+) $amuletNameAcronym".r,
      Seq(expectedTotalCC),
      tolerance,
    )

    element.childElement(className("payment-compute")).text should matchText(expectedComputeText)

    element.childElement(className("payment-description")).text should matchText(
      s"\"$expectedDescription\""
    )
  }

  /** Matches the two parts of an app payment where the currently logged user
    * pays to `expectedPartyId` via the given app `provider`:
    * the first entry is the sender locking a amulet,
    * the second entry is the receiver unlocking the amulet and transferring to themselves
    * the part of the transfer that belongs to them.
    *
    * @see https://github.com/DACH-NY/canton-network-node/pull/3787#discussion_r1153496149
    */
  private def matchLockAndTransfer(
      expectedLockedAmount: BigDecimal,
      receiverPartyId: PartyId,
      expectedEntryName: String,
      balanceChangeForSender: BigDecimal,
  )(implicit driver: WebDriverType, env: SpliceTestConsoleEnvironment) = {
    inside(findAll(className("tx-row")).toList) { case paymentTx :: lockTx :: _ =>
      matchTransaction(lockTx)(
        amuletPrice,
        "Sent",
        "App Payment Accepted",
        Some(s"Automation"),
        expectedLockedAmount,
      )

      matchTransaction(paymentTx)(
        amuletPrice,
        "Sent",
        "App Payment Collected",
        Some(s"${expectedAns(receiverPartyId, expectedEntryName)}"),
        balanceChangeForSender,
      )
    }
  }

}
