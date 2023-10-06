package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment.Currency
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, WalletFrontendTestUtil, WalletTestUtil}
import com.daml.network.config.CNNodeConfigTransforms
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
import org.scalatest.Assertion

import java.time.Duration
import java.util.UUID
import scala.collection.parallel.immutable.ParVector

class WalletTransactionHistoryFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletTxLogTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil {

  private val coinPrice = 2

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .withCoinPrice(coinPrice)

  "A wallet transaction history UI" should {

    "show all types of transactions" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceEntryName = perTestCaseName("alice")

      waitForWalletUser(aliceValidatorWalletClient)
      val aliceValidatorParty = aliceValidatorWalletClient.userStatus().party

      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val charlieEntryName = perTestCaseName("charlie")
      createDirectoryEntry(
        charlieUserParty,
        charlieDirectoryClient,
        charlieEntryName,
        charlieWalletClient,
      )

      val directoryExpectedCns = createDirectoryEntryForDirectoryItself

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice goes to her wallet", {
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees no transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 0
          },
        )

        val (_, txs) = actAndCheck(
          "Transactions are done", {
            // alice's directory - also taps 5 CC
            createDirectoryEntry(
              aliceUserParty,
              aliceDirectoryClient,
              aliceEntryName,
              aliceWalletClient,
            )
            // charlie -> alice
            charlieWalletClient.tap(50)
            p2pTransfer(
              aliceValidatorBackend,
              charlieWalletClient,
              aliceWalletClient,
              aliceUserParty,
              BigDecimal("1.07"),
            )
            // alice -> charlie
            p2pTransfer(
              aliceValidatorBackend,
              aliceWalletClient,
              charlieWalletClient,
              charlieUserParty,
              BigDecimal("1.18"),
            )
            // one-time payment
            val (cid, _) = createPaymentRequest(
              aliceValidatorBackend.participantClientWithAdminToken,
              aliceDamlUser,
              aliceUserParty,
              receiverAmounts = Seq(
                receiverAmount(charlieUserParty, BigDecimal("1.31415"), Currency.CC)
              ),
            )
            eventuallySucceeds() {
              aliceWalletClient.acceptAppPaymentRequest(cid)
            }
          },
        )(
          "Alice sees the transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            txs should have size 6
            txs
          },
        )

        inside(txs) {
          case otp +: sent +: received +: directoryCreation +: lockForDirectory +: balanceChange +: Nil =>
            matchTransaction(otp)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedSubtype = "App Payment Accepted",
              expectedPartyDescription = Some(s"Automation $aliceValidatorParty"),
              expectedAmountCC = BigDecimal("-1.31415"),
            )
            matchTransaction(sent)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedSubtype = "P2P Payment Completed",
              expectedPartyDescription = Some(
                s"${expectedCns(charlieUserParty, charlieEntryName)} $aliceValidatorParty"
              ),
              expectedAmountCC = BigDecimal("-1.18"),
            )
            matchTransaction(received)(
              coinPrice = 2,
              expectedAction = "Received",
              expectedSubtype = "P2P Payment Completed",
              expectedPartyDescription = Some(
                s"${expectedCns(charlieUserParty, charlieEntryName)} $aliceValidatorParty"
              ),
              expectedAmountCC = BigDecimal("1.07"),
            )
            // Note: this transfer has no effect on the balance of the sender:
            // the input for the app payment is a locked coin that was unlocked in the same transaction.
            matchTransaction(directoryCreation)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedSubtype = "Subscription Initial Payment Collected",
              expectedPartyDescription = Some(s"$directoryExpectedCns $directoryExpectedCns"),
              expectedAmountCC = BigDecimal(0), // 0 USD
            )
            matchTransaction(lockForDirectory)(
              coinPrice = 2,
              expectedAction = "Sent",
              expectedSubtype = "Subscription Initial Payment Accepted",
              expectedPartyDescription = Some(s"Automation $aliceValidatorParty"),
              expectedAmountCC = BigDecimal("-0.5"), // 1 USD
            )
            matchTransaction(balanceChange)(
              coinPrice = 2,
              expectedAction = "Balance Change",
              expectedSubtype = "Tap",
              expectedPartyDescription = None,
              expectedAmountCC = BigDecimal(5),
            )
        }
      }
    }

    "paginate transactions" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceEntryName = perTestCaseName("alice")
      waitForWalletUser(aliceValidatorWalletClient)

      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val bobEntryName = perTestCaseName("bob")
      waitForWalletUser(bobValidatorWalletClient)

      val transferAmounts = ParVector.range(1, 20)

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice goes to her wallet", {
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees no transactions",
          _ => {
            txRows should have size 0
          },
        )

        createDirectoryEntry(
          aliceUserParty,
          aliceDirectoryClient,
          aliceEntryName,
          aliceWalletClient,
        )
        createDirectoryEntry(bobUserParty, bobDirectoryClient, bobEntryName, bobWalletClient)

        aliceWalletClient.tap(500)

        actAndCheck(
          "Alice makes transfers to bob", {
            transferAmounts.foreach(amount =>
              p2pTransfer(
                aliceValidatorBackend,
                aliceWalletClient,
                bobWalletClient,
                bobUserParty,
                BigDecimal(amount),
              )
            )
          },
        )(
          "Alice sees the first page of transactions",
          _ => {
            // transactions are paginated in 10s and allowing for automation txs
            assertTxsInRangeAndButtonHasCorrectLabel((8, 12))
          },
        )

        actAndCheck(
          "Load second page", {
            click on id("view-more-transactions")
          },
        )(
          "Alice sees second page of transactions appended",
          _ => {
            assertTxsInRangeAndButtonHasCorrectLabel((18, 25))
          },
        )

        actAndCheck(
          "Load third page", {
            click on id("view-more-transactions")
          },
        )(
          "Alice sees third page of transactions appended",
          _ => {
            assertTxsInRangeAndButtonHasCorrectLabel((18, 25))
          },
        )

        // we have to click the button one last time to know there's no more data to fetch
        actAndCheck(
          "Load final empty page", {
            click on id("view-more-transactions")
          },
        )(
          "Alice sees there are no more transactions to load",
          _ => {
            assertTxsInRangeAndButtonHasCorrectLabel((20, 25), hasMore = false)
          },
        )

      }
    }

    "shows notification transactions" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val validatorTxLogBefore = aliceValidatorWalletClient.listTransactions(None, 1000)

      val (offerCid, _) =
        actAndCheck(
          "Alice creates transfer offer",
          aliceWalletClient.createTransferOffer(
            bobUserParty,
            1000.0,
            "direct transfer test",
            CantonTimestamp.now().plus(Duration.ofMinutes(1)),
            UUID.randomUUID.toString,
          ),
        )(
          "Bob sees transfer offer",
          _ => bobWalletClient.listTransferOffers() should have length 1,
        )

      clue("Bob accepts transfer offer") {
        bobWalletClient.acceptTransferOffer(offerCid)
        // At this point, Alice's automation fails to complete the accepted offer
      }

      checkTxHistory(
        aliceWalletClient,
        Seq({ case logEntry: walletLogEntry.Notification =>
          logEntry.transactionSubtype shouldBe walletLogEntry.Notification.DirectTransferFailed
          logEntry.details should startWith("ITR_InsufficientFunds")
        }),
      )

      // Only Alice should see notification (note that aliceValidator is shared between tests)
      val validatorTxLogAfter = aliceValidatorWalletClient.listTransactions(None, 1000)
      validatorTxLogBefore should be(validatorTxLogAfter)
      checkTxHistory(bobWalletClient, Seq.empty)

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceWalletClient.config.ledgerApiUser)
        val notifications = find(className("tx-row-notification"))
        notifications.fold(fail("Unable to find notifiation transaction row")) { notification =>
          notification.childElement(className("tx-action")).text shouldBe "Notification"
          notification
            .childElement(className("tx-subtype"))
            .text
            .replaceAll("[()]", "") shouldBe "P2P Payment Failed"
        }
      }
    }
  }

  def viewMoreButton(implicit webdriver: WebDriverType): Element = {
    find(id("view-more-transactions"))
      .getOrElse(
        fail("Unable to find button with id view-more-transactions")
      )
  }

  def txRows(implicit webdriver: WebDriverType): Seq[Element] = {
    findAll(className("tx-row")).toSeq
  }

  def assertTxsInRangeAndButtonHasCorrectLabel(range: (Int, Int), hasMore: Boolean = true)(implicit
      webDriverType: WebDriverType
  ): Assertion = {
    val buttonText = viewMoreButton.text
    val label = if (hasMore) "Load More" else "Nothing more to load"
    assertInRange(txRows.size, (BigDecimal(range._1), BigDecimal(range._2)))
    buttonText should be(label)
  }

}
