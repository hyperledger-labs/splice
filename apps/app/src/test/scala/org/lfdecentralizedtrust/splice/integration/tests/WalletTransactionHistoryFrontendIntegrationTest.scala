package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as paymentCodegen
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  SpliceUtil,
  SynchronizerFeesTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.wallet.store.{
  NotificationTxLogEntry,
  TxLogEntry as walletLogEntry,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.scalatest.Assertion

import java.time.Duration
import java.util.UUID
import scala.collection.parallel.immutable.ParVector
import scala.jdk.OptionConverters.*
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson

class WalletTransactionHistoryFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice", "sv1", "scan")
    with WalletTestUtil
    with WalletTxLogTestUtil
    with WalletFrontendTestUtil
    with SynchronizerFeesTestUtil
    with FrontendLoginUtil {

  private val amuletPrice = 2
  override def walletAmuletPrice: java.math.BigDecimal =
    SpliceUtil.damlDecimal(amuletPrice.toDouble)

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .withAmuletPrice(amuletPrice)
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs_(
          _.focus(_.transferPreapproval)
            // set renewal duration to be same as pre-approval lifetime
            // to ensure renewal gets triggered immediately
            .modify(c => c.copy(renewalDuration = c.preapprovalLifetime))
        )(config)
      )

  "A wallet transaction history UI" should {

    "show all types of transactions" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceEntryName = perTestCaseName("alice")

      waitForWalletUser(aliceValidatorWalletClient)

      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val charlieEntryName = perTestCaseName("charlie")
      createAnsEntry(
        charlieAnsExternalClient,
        charlieEntryName,
        charlieWalletClient,
      )

      val dsoEntry = expectedDsoAns

      val updateIds = withFrontEnd("alice") { implicit webDriver =>
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
            // alice's directory - also taps 5 Amulet
            createAnsEntry(
              aliceAnsExternalClient,
              aliceEntryName,
              aliceWalletClient,
              tapAmount = 5 * amuletPrice,
            )
            // charlie -> alice
            charlieWalletClient.tap(50)
            p2pTransfer(charlieWalletClient, aliceWalletClient, aliceUserParty, BigDecimal("1.07"))
            // alice -> charlie
            p2pTransfer(
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
                receiverAmount(
                  charlieUserParty,
                  BigDecimal("1.31415"),
                  paymentCodegen.Unit.AMULETUNIT,
                )
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
          case otp +: sent +: received +: ansCreation +: lockForAns +: balanceChange +: Nil =>
            matchTransaction(otp)(
              amuletPrice = 2,
              expectedAction = "Sent",
              expectedSubtype = "App Payment Accepted",
              expectedPartyDescription = Some(s"Automation"),
              expectedAmountAmulet = BigDecimal("-1.31415"),
            )
            matchTransaction(sent)(
              amuletPrice = 2,
              expectedAction = "Sent",
              expectedSubtype = "P2P Payment Completed",
              expectedPartyDescription = Some(
                s"${expectedAns(charlieUserParty, charlieEntryName)}"
              ),
              expectedAmountAmulet = BigDecimal("-1.18"),
            )
            matchTransaction(received)(
              amuletPrice = 2,
              expectedAction = "Received",
              expectedSubtype = "P2P Payment Completed",
              expectedPartyDescription = Some(
                s"${expectedAns(charlieUserParty, charlieEntryName)}"
              ),
              expectedAmountAmulet = BigDecimal("1.07"),
            )
            // Note: this transfer has no effect on the balance of the sender:
            // the input for the app payment is a locked amulet that was unlocked in the same transaction.
            matchTransaction(ansCreation)(
              amuletPrice = 2,
              expectedAction = "Sent",
              expectedSubtype = s"${ansAcronym.toUpperCase()} Entry Initial Payment Collected",
              expectedPartyDescription = Some(s"$dsoEntry"),
              expectedAmountAmulet = BigDecimal(0), // 0 USD
            )
            matchTransaction(lockForAns)(
              amuletPrice = 2,
              expectedAction = "Sent",
              expectedSubtype = "Subscription Initial Payment Accepted",
              expectedPartyDescription = Some(s"Automation"),
              expectedAmountAmulet = BigDecimal("-0.5"), // 1 USD
            )
            matchTransaction(balanceChange)(
              amuletPrice = 2,
              expectedAction = "Balance Change",
              expectedSubtype = "Tap",
              expectedPartyDescription = None,
              expectedAmountAmulet = BigDecimal(5),
            )
        }

        txs
          .map(row => {
            val updateId = readTransactionFromRow(row).updateId
            updateId should not be empty
            updateId
          })
          // remove the balance change tx for scan comparison
          .init
      }
      withFrontEnd("scan") { implicit webDriver =>
        actAndCheck(
          "Go to Scan",
          go to s"http://localhost:${scanUIPort}",
        )(
          "All transactions appear also in scan UI, with the same update ID",
          _ => {
            updateIds.foreach(updateId => {
              val scanActivities = findAll(className("activity-row")).toSeq
              // Activities do not map 1:1 to updates, a single update may be broken into more than one
              // activity in Scan, so we check for "at least 1" instead of "exactly 1"
              forAtLeast(1, scanActivities) { activity =>
                activity.findChildElement(className("update-id")).map(seleniumText) should be(
                  Some(updateId)
                )
              }
            })
          },
        )
      }
      clue("update IDs from the UI can be used for querying scan") {
        updateIds.foreach(updateId =>
          eventuallySucceeds() {
            sv1ScanBackend.getUpdate(updateId, encoding = CompactJson)
          }
        )
      }
    }

    "show extra traffic purchases" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        val sv1WalletUser = sv1ValidatorBackend.config.validatorWalletUsers.loneElement
        browseToSv1Wallet(sv1WalletUser)
        val trafficAmount = 10_000_000L
        val (_, trafficCostCc) = computeSynchronizerFees(trafficAmount)
        actAndCheck(
          "SV1 purchases extra traffic",
          buyMemberTraffic(sv1ValidatorBackend, trafficAmount, env.environment.clock.now),
        )(
          "SV1 sees the transaction",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            forExactly(1, txs) { tx =>
              matchTransaction(tx)(
                amuletPrice = 2,
                expectedAction = "Sent",
                expectedSubtype = "Extra Traffic Purchase",
                expectedPartyDescription = Some("Automation"),
                expectedAmountAmulet = -trafficCostCc,
              )
            }
          },
        )
      }
    }

    "paginate transactions" in { implicit env =>
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
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

        createAnsEntry(
          aliceAnsExternalClient,
          aliceEntryName,
          aliceWalletClient,
        )
        createAnsEntry(bobAnsExternalClient, bobEntryName, bobWalletClient)

        aliceWalletClient.tap(500)

        actAndCheck(
          "Alice makes transfers to bob", {
            transferAmounts.foreach(amount =>
              p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, BigDecimal(amount))
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
            eventuallyClickOn(id("view-more-transactions"))
          },
        )(
          "Alice sees second page of transactions appended",
          _ => {
            assertTxsInRangeAndButtonHasCorrectLabel((18, 25))
          },
        )

        actAndCheck(
          "Load third page", {
            eventuallyClickOn(id("view-more-transactions"))
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
            eventuallyClickOn(id("view-more-transactions"))
          },
        )(
          "Alice sees there are no more transactions to load",
          _ => {
            assertTxsInRangeAndButtonHasCorrectLabel((20, 25), hasMore = false)
          },
        )

      }
    }

    "show notification transactions" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val validatorTxLogBefore =
        withoutDevNetTopups(aliceValidatorWalletClient.listTransactions(None, 1000))

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
        Seq({ case logEntry: NotificationTxLogEntry =>
          logEntry.subtype.value shouldBe walletLogEntry.NotificationTransactionSubtype.DirectTransferFailed.toProto
          logEntry.details should startWith("ITR_InsufficientFunds")
        }),
      )

      // Only Alice should see notification (note that aliceValidator is shared between tests)
      val validatorTxLogAfter =
        withoutDevNetTopups(aliceValidatorWalletClient.listTransactions(None, 1000))
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
          notification.findChildElement(className("update-id")) shouldBe None
        }
      }
    }

    "show transfer preapproval purchases and renewals" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        val sv1ValidatorWalletUser = sv1ValidatorBackend.config.validatorWalletUsers.loneElement
        val amuletConfig = sv1ScanBackend.getAmuletConfigAsOf(env.environment.clock.now)
        val preapprovalFeeRate = amuletConfig.transferPreapprovalFee.toScala.map(BigDecimal(_))
        val (_, preapprovalFee) = SpliceUtil.transferPreapprovalFees(
          bobValidatorBackend.config.transferPreapproval.preapprovalLifetime,
          preapprovalFeeRate,
          amuletPrice,
        )
        sv1WalletClient.tap(10.0)
        browseToSv1Wallet(sv1ValidatorWalletUser)
        actAndCheck(
          "SV1 creates a transfer preapproval and automation renews it immediately",
          createTransferPreapprovalEnsuringItExists(sv1WalletClient, sv1ValidatorBackend),
        )(
          "SV1 sees the creation and renewal transactions",
          _ => {
            val txs = findAll(className("tx-row")).toSeq
            forExactly(1, txs) { tx =>
              matchTransaction(tx)(
                amuletPrice = 2,
                expectedAction = "Sent",
                expectedSubtype = "Transfer Preapproval Created",
                expectedPartyDescription = Some("Automation"),
                expectedAmountAmulet = -preapprovalFee,
              )
            }
            forExactly(1, txs) { tx =>
              matchTransaction(tx)(
                amuletPrice = 2,
                expectedAction = "Sent",
                expectedSubtype = "Transfer Preapproval Renewed",
                expectedPartyDescription = Some("Automation"),
                expectedAmountAmulet = -preapprovalFee,
              )
            }
          },
        )
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
