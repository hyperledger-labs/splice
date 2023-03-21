package com.daml.network.integration.tests

import com.daml.lf.data.Numeric
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTestWithSharedEnvironment
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Duration
import java.util.UUID

class WalletTxLogIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition: CoinEnvironmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndCoinMerging
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(0.75)(config))
      // Some tests use the splitwell app to generate multi-party payments
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(splitwellDarPath)
        bobValidator.remoteParticipant.dars.upload(splitwellDarPath)
      })
  }

  "A wallet" should {

    // TODO(#2837) Extend these tests

    "handle tap" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      clue("Tap to get some coins") {
        aliceWallet.tap(11.0)
        aliceWallet.tap(12.0)
      }

      checkTxHistory(
        aliceWallet,
        Seq(
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 11.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 12.0
          },
        ),
      )
    }

    "handle collected self-payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      clue("Tap to get some coins") {
        aliceWallet.tap(10.0)
        aliceWallet.tap(20.0)
        aliceWallet.tap(30.0)
      }

      val ((_, reqCid, _), _) = actAndCheck(
        "Alice creates self-payment request",
        createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Alice sees the self-payment request",
        _ => aliceWallet.listAppPaymentRequests() should not be empty,
      )

      val (acceptedPaymentCid, _) = actAndCheck(
        "Alice accepts the self-payment request",
        aliceWallet.acceptAppPaymentRequest(reqCid),
      )(
        "Payment request disappears from list",
        _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
      )

      actAndCheck(
        "Alice collects self-payment request",
        collectAcceptedAppPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          Seq(aliceUserParty),
          acceptedPaymentCid,
        ),
      )(
        "Accepted app payment disappears",
        _ => aliceWallet.listAcceptedAppPayments() shouldBe empty,
      )

      checkTxHistory(
        aliceWallet,
        Seq(
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 10.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 20.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 30.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the self-payment request created a 10CC locked coin,
            // leading to a net loss of slightly over 10CC because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(selfPaymentAmount, selfPaymentAmount + smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // First part of collecting the payment: Unlocking the 10CC locked coin.
            // TODO(#3486) - this and the next entry should be merged
            logEntry.amount should beWithin(selfPaymentAmount, selfPaymentAmount + smallAmount)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Second part of collecting the payment: Transferring the coin to ourselves.
            // Note: this and the previous entry should really be merged in the history.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-smallAmount, smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
        ),
      )
    }

    "handle rejected self-payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      clue("Tap to get some coins") {
        aliceWallet.tap(10.0)
        aliceWallet.tap(20.0)
        aliceWallet.tap(30.0)
      }

      val ((_, reqCid, _), _) = actAndCheck(
        "Alice creates self-payment request",
        createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Alice sees the self-payment request",
        _ => aliceWallet.listAppPaymentRequests() should not be empty,
      )

      val (acceptedPaymentCid, _) = actAndCheck(
        "Alice accepts the self-payment request",
        aliceWallet.acceptAppPaymentRequest(reqCid),
      )(
        "Payment request disappears from list",
        _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
      )

      actAndCheck(
        "Alice rejects the self-payment request",
        rejectAcceptedAppPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          acceptedPaymentCid,
        ),
      )(
        "Accepted app payment disappears",
        _ => aliceWallet.listAcceptedAppPayments() shouldBe empty,
      )

      checkTxHistory(
        aliceWallet,
        Seq(
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 10.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 20.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 30.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the self-payment request created a 10CC locked coin,
            // leading to a net loss of slightly over 10CC because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(selfPaymentAmount, selfPaymentAmount + smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // Rejecting the accepted self-payment request returned the 10CC locked coin.
            logEntry.amount should beWithin(selfPaymentAmount, selfPaymentAmount + smallAmount)
          },
        ),
      )
    }

    "handle mixed currency payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
      val aliceValidatorUserParty = aliceValidator.getValidatorPartyId()
      val coinPrice = scan.getLatestOpenMiningRound(env.environment.clock.now).payload.coinPrice
      assert(coinPrice.compareTo(BigDecimal(1.0)) != 0, "Test is more useful if CC != USD")

      // Based on Numeric to make sure divisions match Decimal computation in Daml.
      val transferAmountCC = Numeric.assertFromBigDecimal(scale, 22.0)
      val transferAmountUSD = Numeric.assertFromBigDecimal(scale, 20.0)
      val transferAmountUSDinCC = Numeric
        .divide(scale, transferAmountUSD, Numeric.assertFromBigDecimal(scale, coinPrice))
        .value
      val transferAmountTotalCC = transferAmountCC.add(transferAmountUSDinCC)

      clue("Tap to get some coins") {
        aliceWallet.tap(100.0)
      }

      val ((_, reqCid, _), _) = actAndCheck(
        "Alice creates payment request",
        createPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, transferAmountCC, walletCodegen.Currency.CC),
            receiverAmount(aliceValidatorUserParty, transferAmountUSD, walletCodegen.Currency.USD),
          ),
        ),
      )(
        "Alice sees the payment request",
        _ => aliceWallet.listAppPaymentRequests() should not be empty,
      )

      val (acceptedPaymentCid, _) = actAndCheck(
        "Alice accepts the payment request",
        aliceWallet.acceptAppPaymentRequest(reqCid),
      )(
        "Payment request disappears from list",
        _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
      )

      actAndCheck(
        "Receivers collect the payment request",
        collectAcceptedAppPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          Seq(aliceUserParty, charlieUserParty, aliceValidatorUserParty),
          acceptedPaymentCid,
        ),
      )(
        "Accepted app payment disappears",
        _ => aliceWallet.listAcceptedAppPayments() shouldBe empty,
      )

      checkTxHistory(
        aliceWallet,
        Seq(
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 100.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the payment request created a locked coin,
            // leading to a net loss because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(
                BigDecimal(transferAmountTotalCC),
                BigDecimal(transferAmountTotalCC) + smallAmount,
              )
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // First part of collecting the payment: Unlocking the locked coin.
            // TODO(#3486) - this and the next entry should be merged
            logEntry.amount should beWithin(
              transferAmountTotalCC,
              BigDecimal(transferAmountTotalCC) + smallAmount,
            )
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Second part of collecting the payment: Transferring the coin to the receivers
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(
                transferAmountTotalCC,
                BigDecimal(transferAmountTotalCC) + smallAmount,
              )
            }
            inside(logEntry.receivers) { case Seq((receiver1, amount1), (receiver2, amount2)) =>
              receiver1 shouldBe charlieUserParty.toProtoPrimitive
              amount1 should beWithin(BigDecimal(transferAmountCC) - smallAmount, transferAmountCC)

              receiver2 shouldBe aliceValidatorUserParty.toProtoPrimitive
              amount2 shouldBe BigDecimal(transferAmountUSDinCC)
            }
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
        ),
      )
    }

    "handle completed transfer offers" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)

      val transferAmount = 32.0

      clue("Alice taps some coins") {
        aliceWallet.tap(100.0)
      }

      val (offerCid, _) =
        actAndCheck(
          "Alice creates transfer offer",
          aliceWallet.createTransferOffer(
            bobUserParty,
            transferAmount,
            "direct transfer test",
            CantonTimestamp.now().plus(Duration.ofMinutes(1)),
            UUID.randomUUID.toString,
          ),
        )("Bob sees transfer offer", _ => bobWallet.listTransferOffers() should have length 1)

      val previousQuantity = aliceWallet.balance().unlockedQty

      actAndCheck("Bob accepts transfer offer", bobWallet.acceptTransferOffer(offerCid))(
        "Alice does not see transfer offer anymore",
        _ => aliceWallet.listTransferOffers() shouldBe empty,
      )

      clue("Wait until Alice's wallet automation transfers") {
        eventually() {
          bobWallet.balance().unlockedQty should be > BigDecimal(0)
          aliceWallet.balance().unlockedQty should be < (previousQuantity - transferAmount)
        }
      }

      // Both Alice and Bob see the same representation of the transfer
      val checkTransfer: CheckTxHistoryFn = {
        case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
          inside(logEntry.sender) { case (sender, amount) =>
            sender shouldBe aliceUserParty.toProtoPrimitive
            amount should beWithin(transferAmount, transferAmount + smallAmount)
          }

          inside(logEntry.receivers) { case Seq((receiver, amount)) =>
            receiver shouldBe bobUserParty.toProtoPrimitive
            amount shouldBe transferAmount
          }

          logEntry.senderHoldingFees shouldBe BigDecimal(0)
      }

      checkTxHistory(
        aliceWallet,
        Seq(
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 100.0
          },
          checkTransfer,
        ),
      )

      checkTxHistory(
        bobWallet,
        Seq(
          checkTransfer
        ),
      )
    }

    "handle collected multi-party app payment" in { implicit env =>
      // Note: multi-party payments are difficult to execute manually.
      // This test therefore uses the splitwell app to generate such a payment.
      val (aliceUserParty, bobUserParty, charlieUserParty, _, key, invite) =
        initSplitwellTest()

      // Note: initSplitwellTest() adds Bob to the group
      clue("Bob was already added to the group") {
        bobSplitwell.listGroups() should have size 1
        aliceSplitwell.listAcceptedGroupInvites(key.id) should be(empty)
      }

      // Note: initSplitwellTest() does not add Charlie to the group
      actAndCheck(
        "Charlie accepts the invite",
        charlieSplitwell.acceptInvite(invite),
      )(
        "Alice sees the accepted invite",
        _ => aliceSplitwell.listAcceptedGroupInvites(key.id) should not be empty,
      )

      actAndCheck(
        "Alice joins all accepted invites",
        aliceSplitwell
          .listAcceptedGroupInvites(key.id)
          .foreach(accepted => aliceSplitwell.joinGroup(accepted.contractId)),
      )(
        "Charlie sees the group and Alice doesn't see any accepted invite",
        _ => {
          charlieSplitwell.listGroups() should have size 1
          aliceSplitwell.listAcceptedGroupInvites(key.id) should be(empty)
        },
      )

      actAndCheck("Alice taps some coins", aliceWallet.tap(100.0))(
        "Alice has some coins",
        _ => aliceWallet.balance().unlockedQty should be > BigDecimal(0),
      )

      val (_, paymentRequest) = actAndCheck(
        "Alice initiates a transfer of CC to all other group members",
        aliceSplitwell.initiateTransfer(
          key,
          Seq(
            new walletCodegen.ReceiverCCAmount(
              bobUserParty.toProtoPrimitive,
              new java.math.BigDecimal(20.0),
            ),
            new walletCodegen.ReceiverCCAmount(
              charlieUserParty.toProtoPrimitive,
              new java.math.BigDecimal(30.0),
            ),
          ),
        ),
      )(
        "Alice sees the app payment request on the global domain",
        _ => aliceWallet.listAppPaymentRequests().headOption.value,
      )

      actAndCheck(
        "Alice confirms the payment request",
        aliceWallet.acceptAppPaymentRequest(paymentRequest.contractId),
      )(
        "All parties see new balances",
        _ => {
          aliceWallet.listAcceptedAppPayments() shouldBe empty
          bobWallet.balance().unlockedQty should be > BigDecimal(0.0)
          charlieWallet.balance().unlockedQty should be > BigDecimal(0.0)
        },
      )

      // All parties see the same representation of the transfer
      val checkTransfer: CheckTxHistoryFn = {
        case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
          // This is the actual payment, transferring the unlocked coin to
          // the receivers
          inside(logEntry.sender) { case (sender, amount) =>
            sender shouldBe aliceUserParty.toProtoPrimitive
            amount should beWithin(50, 50 + smallAmount)
          }

          inside(logEntry.receivers) { case Seq((receiver1, amount1), (receiver2, amount2)) =>
            receiver1 shouldBe bobUserParty.toProtoPrimitive
            amount1 shouldBe 20

            receiver2 shouldBe charlieUserParty.toProtoPrimitive
            amount2 shouldBe 30
          }

          logEntry.senderHoldingFees shouldBe BigDecimal(0)
      }

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 100.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the self-payment request created a 50CC locked coin,
            // leading to a net loss of slightly over 50CC because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(50, 50 + smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // First part of collecting the payment: Unlocking the 50CC locked coin.
            // TODO(#3486) - this and the next entry should be merged
            logEntry.amount should beWithin(50, 50 + smallAmount)
          },
          checkTransfer,
        ),
      )

      checkTxHistory(
        bobWallet,
        Seq(checkTransfer),
      )

      checkTxHistory(
        charlieWallet,
        Seq(checkTransfer),
      )
    }

    "handle collected subscription payments" in { implicit env =>
      val aliceUserId = aliceWallet.config.ledgerApiUser
      val charlieUserId = charlieWallet.config.ledgerApiUser

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)

      val subscriptionPrice = 42.0

      clue("Alice taps some coins") {
        aliceWallet.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceUserId,
          aliceUserParty,
          charlieUserParty,
          charlieUserParty,
          paymentAmount(subscriptionPrice, walletCodegen.Currency.CC),
          paymentInterval = Duration.ofMinutes(60),
          paymentDuration = Duration.ofMinutes(60),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWallet.listSubscriptionRequests().headOption.value,
      )

      val (initialPaymentCid, _) = actAndCheck(
        "Alice accepts the request",
        aliceWallet.acceptSubscriptionRequest(request.contractId),
      )(
        "Request disappears from Alice's list",
        _ => {
          aliceWallet.listSubscriptionRequests() shouldBe empty
        },
      )

      actAndCheck(
        "Charlie collects the initial payment",
        collectAcceptedSubscriptionRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          charlieUserId,
          charlieUserParty,
          aliceUserParty,
          initialPaymentCid,
        ),
      )(
        "Charlie's balance reflects the collected payment",
        _ => charlieWallet.balance().unlockedQty should be > BigDecimal(40),
      )

      // Note: because paymentInterval == paymentDuration, the second payment can be made immediately
      val paymentCid = clue("Alice's automation triggers the second payment") {
        eventually() {
          inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
            sub.main.payload should equal(request.payload.subscriptionData)
            inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
              state.payload.subscription shouldBe sub.main.contractId
              state.payload.payData should equal(request.payload.payData)
              state.contractId
            }
          }
        }
      }

      actAndCheck(
        "Charlie collects the second payment",
        collectSubscriptionPayment(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          charlieUserId,
          charlieUserParty,
          aliceUserParty,
          paymentCid,
        ),
      )(
        "Charlie's balance reflects the collected payment",
        _ => charlieWallet.balance().unlockedQty should be > BigDecimal(80),
      )

      // All parties see the same representation of the transfer
      val checkSubscriptionPaymentTransfer: CheckTxHistoryFn = {
        case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
          // This is the actual payment, transferring the unlocked coin to
          // the receivers
          inside(logEntry.sender) { case (sender, amount) =>
            sender shouldBe aliceUserParty.toProtoPrimitive
            amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          }

          inside(logEntry.receivers) { case Seq((receiver, amount)) =>
            receiver shouldBe charlieUserParty.toProtoPrimitive
            amount shouldBe subscriptionPrice
          }

          logEntry.senderHoldingFees shouldBe BigDecimal(0)
      }

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 100.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // First part of collecting the payment: Unlocking the 42CC locked coin.
            // TODO(#3486) - this and the next entry should be merged
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          },
          checkSubscriptionPaymentTransfer,
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // First part of collecting the payment: Unlocking the 42CC locked coin.
            // TODO(#3486) - this and the next entry should be merged
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          },
          checkSubscriptionPaymentTransfer,
        ),
      )

      checkTxHistory(
        charlieWallet,
        Seq(checkSubscriptionPaymentTransfer, checkSubscriptionPaymentTransfer),
      )
    }

    "handle rejected subscription initial payments" in { implicit env =>
      val aliceUserId = aliceWallet.config.ledgerApiUser
      val charlieUserId = charlieWallet.config.ledgerApiUser

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)

      val subscriptionPrice = 42.0

      clue("Alice taps some coins") {
        aliceWallet.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceUserId,
          aliceUserParty,
          charlieUserParty,
          charlieUserParty,
          paymentAmount(subscriptionPrice, walletCodegen.Currency.CC),
          paymentInterval = Duration.ofMinutes(60),
          paymentDuration = Duration.ofMinutes(60),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWallet.listSubscriptionRequests().headOption.value,
      )

      val (initialPaymentCid, _) = actAndCheck(
        "Alice accepts the request",
        aliceWallet.acceptSubscriptionRequest(request.contractId),
      )(
        "Request disappears from Alice's list",
        _ => {
          charlieWallet.listSubscriptionInitialPayments()
          aliceWallet.listSubscriptionRequests() shouldBe empty
        },
      )

      actAndCheck(
        "Charlie rejects the initial payment",
        rejectAcceptedSubscriptionRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          charlieUserId,
          charlieUserParty,
          initialPaymentCid,
        ),
      )(
        "Alice's balance reflects the returned locked coin",
        _ => aliceWallet.balance().unlockedQty should be > (BigDecimal(100) - smallAmount),
      )

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 100.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // Rejecting the accepted subscription request returned the 42CC locked coin.
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          },
        ),
      )

      checkTxHistory(
        charlieWallet,
        Seq.empty,
      )
    }

    "handle rejected subscription payments" in { implicit env =>
      val aliceUserId = aliceWallet.config.ledgerApiUser
      val charlieUserId = charlieWallet.config.ledgerApiUser

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)

      val subscriptionPrice = BigDecimal(42.0)

      clue("Alice taps some coins") {
        aliceWallet.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceUserId,
          aliceUserParty,
          charlieUserParty,
          charlieUserParty,
          paymentAmount(subscriptionPrice, walletCodegen.Currency.CC),
          paymentInterval = Duration.ofMinutes(60),
          paymentDuration = Duration.ofMinutes(60),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWallet.listSubscriptionRequests().headOption.value,
      )

      val (initialPaymentCid, _) = actAndCheck(
        "Alice accepts the request",
        aliceWallet.acceptSubscriptionRequest(request.contractId),
      )(
        "Request disappears from Alice's list",
        _ => {
          aliceWallet.listSubscriptionRequests() shouldBe empty
        },
      )

      actAndCheck(
        "Charlie collects the initial payment",
        collectAcceptedSubscriptionRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          charlieUserId,
          charlieUserParty,
          aliceUserParty,
          initialPaymentCid,
        ),
      )(
        "Charlie's balance reflects the collected payment",
        _ => charlieWallet.balance().unlockedQty should be > BigDecimal(40),
      )

      // Note: because paymentInterval == paymentDuration, the second payment can be made immediately
      val paymentCid = clue("Alice's automation triggers the second payment") {
        eventually() {
          inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
            sub.main.payload should equal(request.payload.subscriptionData)
            inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
              state.payload.subscription shouldBe sub.main.contractId
              state.payload.payData should equal(request.payload.payData)
              state.contractId
            }
          }
        }
      }

      actAndCheck(
        "Charlie rejects the second payment",
        rejectSubscriptionPayment(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          charlieUserId,
          charlieUserParty,
          paymentCid,
        ),
      )(
        "Alice's balance reflects the returned locked coin",
        _ =>
          aliceWallet.balance().unlockedQty should be > (BigDecimal(
            100
          ) - subscriptionPrice - smallAmount),
      )

      // All parties see the same representation of the transfer
      val checkSubscriptionPaymentTransfer: CheckTxHistoryFn = {
        case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
          // This is the actual payment, transferring the unlocked coin to
          // the receivers
          inside(logEntry.sender) { case (sender, amount) =>
            sender shouldBe aliceUserParty.toProtoPrimitive
            amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          }

          inside(logEntry.receivers) { case Seq((receiver, amount)) =>
            receiver shouldBe charlieUserParty.toProtoPrimitive
            amount shouldBe subscriptionPrice
          }

          logEntry.senderHoldingFees shouldBe BigDecimal(0)
      }

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            logEntry.amount shouldBe 100.0
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // First part of collecting the payment: Unlocking the 42CC locked coin.
            // TODO(#3486) - this and the next entry should be merged
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          },
          checkSubscriptionPaymentTransfer,
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // Rejecting the second payment returned the 42CC locked coin.
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          },
        ),
      )

      checkTxHistory(
        charlieWallet,
        Seq(checkSubscriptionPaymentTransfer),
      )
    }
  }

}
