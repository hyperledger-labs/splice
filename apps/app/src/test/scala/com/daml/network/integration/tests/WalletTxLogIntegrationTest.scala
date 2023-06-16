package com.daml.network.integration.tests

import com.daml.lf.data.Numeric
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry.TransactionSubtype
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.time.Duration
import java.util.UUID

class WalletTxLogIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  private val coinPrice = BigDecimal(0.75).setScale(10)

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyX(this.getClass.getSimpleName)
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndCoinMerging
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
      // Some tests use the splitwell app to generate multi-party payments
      .withAdditionalSetup(implicit env => {
        aliceValidator.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidator.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
  }

  "A wallet" should {

    "handle tap" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      clue("Tap to get some coins") {
        aliceWallet.tap(11.0)
        aliceWallet.tap(12.0)
      }

      checkTxHistory(
        aliceWallet,
        Seq(
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 12.0
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 11.0
            logEntry.coinPrice shouldBe coinPrice
          },
        ),
      )
    }

    "handle mint" in { implicit env =>
      val sv1UserParty = onboardWalletUser(sv1Wallet, sv1Validator)

      clue("Mint to get some coins") {
        mintCoin(
          sv1Validator.participantClientWithAdminToken,
          sv1UserParty,
          47.0,
        )
      }

      checkTxHistory(
        sv1Wallet,
        Seq(
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Mint
            logEntry.amount shouldBe 47.0
            logEntry.coinPrice shouldBe coinPrice
          }
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
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Alice sees the self-payment request",
        _ => aliceWallet.listAppPaymentRequests() should not be empty,
      )

      val (_, acceptedPayment) = actAndCheck(
        "Alice accepts the self-payment request",
        aliceWallet.acceptAppPaymentRequest(reqCid),
      )(
        "Payment request disappears from list",
        acceptedPaymentCid => {
          aliceWallet.listAppPaymentRequests() shouldBe empty
          aliceWallet.listAcceptedAppPayments().find(_.contractId == acceptedPaymentCid).value
        },
      )

      actAndCheck(
        "Alice collects self-payment request",
        collectAcceptedAppPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          Seq(aliceUserParty),
          acceptedPayment,
        ),
      )(
        "Accepted app payment disappears",
        _ => aliceWallet.listAcceptedAppPayments() shouldBe empty,
      )

      checkTxHistory(
        aliceWallet,
        Seq(
          { case logEntry: walletLogEntry.Transfer =>
            // Second part of collecting the payment: Transferring the coin to ourselves.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.AppPaymentCollected
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(selfPaymentAmount - smallAmount, selfPaymentAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the self-payment request created a 10CC locked coin,
            // leading to a net loss of slightly over 10CC because of fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.AppPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-selfPaymentAmount - smallAmount, -selfPaymentAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 30.0
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 20.0
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 10.0
            logEntry.coinPrice shouldBe coinPrice
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
          aliceValidator.participantClientWithAdminToken,
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
          aliceValidator.participantClientWithAdminToken,
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
          { case logEntry: walletLogEntry.BalanceChange =>
            // Rejecting the accepted self-payment request returned the 10CC locked coin.
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.AppPaymentRejected
            logEntry.amount should beWithin(selfPaymentAmount, selfPaymentAmount + smallAmount)
          },
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the self-payment request created a 10CC locked coin,
            // leading to a net loss of slightly over 10CC because of transfer fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.AppPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-selfPaymentAmount - smallAmount, -selfPaymentAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 30.0
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 20.0
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 10.0
            logEntry.coinPrice shouldBe coinPrice
          },
        ),
      )
    }

    "handle mixed currency payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
      val aliceValidatorUserParty = aliceValidator.getValidatorPartyId()

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
          aliceValidator.participantClientWithAdminToken,
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

      val (_, acceptedPayment) = actAndCheck(
        "Alice accepts the payment request",
        aliceWallet.acceptAppPaymentRequest(reqCid),
      )(
        "Payment request disappears from list",
        acceptedPaymentCid => {
          aliceWallet.listAppPaymentRequests() shouldBe empty
          aliceWallet.listAcceptedAppPayments().find(_.contractId == acceptedPaymentCid).value
        },
      )

      actAndCheck(
        "Receivers collect the payment request",
        collectAcceptedAppPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          Seq(aliceUserParty, charlieUserParty, aliceValidatorUserParty),
          acceptedPayment,
        ),
      )(
        "Accepted app payment disappears",
        _ => aliceWallet.listAcceptedAppPayments() shouldBe empty,
      )

      checkTxHistory(
        aliceWallet,
        Seq(
          { case logEntry: walletLogEntry.Transfer =>
            // Collecting the payment: Transferring the coin to the receivers
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.AppPaymentCollected
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount shouldBe BigDecimal(0)
            }
            inside(logEntry.receivers) { case Seq((receiver1, amount1), (receiver2, amount2)) =>
              receiver1 shouldBe charlieUserParty.toProtoPrimitive
              amount1 should beWithin(BigDecimal(transferAmountCC) - smallAmount, transferAmountCC)

              receiver2 shouldBe aliceValidatorUserParty.toProtoPrimitive
              amount2 shouldBe BigDecimal(transferAmountUSDinCC)
            }
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the payment request created a locked coin,
            // leading to a net loss because of transfer fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.AppPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(
                -BigDecimal(transferAmountTotalCC) - smallAmount,
                -BigDecimal(transferAmountTotalCC),
              )
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 100.0
            logEntry.coinPrice shouldBe coinPrice
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

      actAndCheck("Bob accepts transfer offer", bobWallet.acceptTransferOffer(offerCid))(
        "Alice does not see transfer offer anymore",
        _ => aliceWallet.listTransferOffers() shouldBe empty,
      )

      // Both Alice and Bob see the same representation of the transfer
      val checkTransfer: CheckTxHistoryFn = { case logEntry: walletLogEntry.Transfer =>
        logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.P2PPaymentCompleted
        inside(logEntry.sender) { case (sender, amount) =>
          sender shouldBe aliceUserParty.toProtoPrimitive
          amount should beWithin(-transferAmount - smallAmount, -transferAmount)
        }

        inside(logEntry.receivers) { case Seq((receiver, amount)) =>
          receiver shouldBe bobUserParty.toProtoPrimitive
          amount shouldBe transferAmount
        }

        logEntry.senderHoldingFees shouldBe BigDecimal(0)
        logEntry.coinPrice shouldBe coinPrice
      }

      checkTxHistory(
        aliceWallet,
        Seq(
          checkTransfer,
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 100.0
            logEntry.coinPrice shouldBe coinPrice
          },
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

      // Note: initSplitwellTest() adds Bob to the group, but not Charlie
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
        aliceWallet.acceptAppPaymentRequest(paymentRequest.appPaymentRequest.contractId),
      )(
        "All parties see new balances",
        _ => {
          aliceWallet.listAcceptedAppPayments() shouldBe empty
          bobWallet.balance().unlockedQty should be > BigDecimal(0.0)
          charlieWallet.balance().unlockedQty should be > BigDecimal(0.0)
        },
      )

      // All parties see the same representation of the transfer
      val checkTransfer: CheckTxHistoryFn = { case logEntry: walletLogEntry.Transfer =>
        // This is the actual payment, transferring the unlocked coin to
        // the receivers
        logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.AppPaymentCollected
        inside(logEntry.sender) { case (sender, amount) =>
          sender shouldBe aliceUserParty.toProtoPrimitive
          amount shouldBe BigDecimal(0)
        }

        inside(logEntry.receivers) { case Seq((receiver1, amount1), (receiver2, amount2)) =>
          receiver1 shouldBe bobUserParty.toProtoPrimitive
          amount1 shouldBe 20

          receiver2 shouldBe charlieUserParty.toProtoPrimitive
          amount2 shouldBe 30
        }

        logEntry.senderHoldingFees shouldBe BigDecimal(0)
        logEntry.coinPrice shouldBe coinPrice
      }

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          checkTransfer,
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the self-payment request created a 50CC locked coin,
            // leading to a net loss of slightly over 50CC because of transfer fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.AppPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-50 - smallAmount, -50)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 100.0
            logEntry.coinPrice shouldBe coinPrice
          },
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
          aliceValidator.participantClientWithAdminToken,
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

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWallet.acceptSubscriptionRequest(request.subscriptionRequest.contractId),
      )(
        "Request disappears from Alice's list",
        initPaymentCid => {
          aliceWallet.listSubscriptionRequests() shouldBe empty
          aliceWallet.listSubscriptionInitialPayments().find(_.contractId == initPaymentCid).value
        },
      )

      actAndCheck(
        "Charlie collects the initial payment",
        collectAcceptedSubscriptionRequest(
          aliceValidator.participantClientWithAdminToken,
          charlieUserId,
          charlieUserParty,
          aliceUserParty,
          initialPayment,
        ),
      )(
        "Charlie's balance reflects the collected payment",
        _ => charlieWallet.balance().unlockedQty should be > BigDecimal(40),
      )

      // Note: because paymentInterval == paymentDuration, the second payment can be made immediately
      val paymentCid = clue("Alice's automation triggers the second payment") {
        eventually() {
          inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
            sub.subscription.payload should equal(
              request.subscriptionRequest.payload.subscriptionData
            )
            inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
              state.payload.subscription shouldBe sub.subscription.contractId
              state.payload.payData should equal(request.subscriptionRequest.payload.payData)
              state.contractId
            }
          }
        }
      }

      actAndCheck(
        "Charlie collects the second payment",
        collectSubscriptionPayment(
          aliceValidator.participantClientWithAdminToken,
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
      def checkSubscriptionPaymentTransfer(
          transactionSubtype: TransactionSubtype
      ): CheckTxHistoryFn = { case logEntry: walletLogEntry.Transfer =>
        // This is the actual payment, transferring the unlocked coin to
        // the receivers
        logEntry.transactionSubtype shouldBe transactionSubtype
        inside(logEntry.sender) { case (sender, amount) =>
          sender shouldBe aliceUserParty.toProtoPrimitive
          amount shouldBe BigDecimal(0)
        }

        inside(logEntry.receivers) { case Seq((receiver, amount)) =>
          receiver shouldBe charlieUserParty.toProtoPrimitive
          amount shouldBe subscriptionPrice
        }

        logEntry.senderHoldingFees shouldBe BigDecimal(0)
        logEntry.coinPrice shouldBe coinPrice
      }

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          checkSubscriptionPaymentTransfer(
            walletLogEntry.Transfer.SubscriptionPaymentCollected
          ),
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.SubscriptionPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-subscriptionPrice - smallAmount, -subscriptionPrice)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          checkSubscriptionPaymentTransfer(
            walletLogEntry.Transfer.SubscriptionInitialPaymentCollected
          ),
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.SubscriptionInitialPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-subscriptionPrice - smallAmount, -subscriptionPrice)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 100.0
            logEntry.coinPrice shouldBe coinPrice
          },
        ),
      )

      checkTxHistory(
        charlieWallet,
        Seq(
          checkSubscriptionPaymentTransfer(
            walletLogEntry.Transfer.SubscriptionPaymentCollected
          ),
          checkSubscriptionPaymentTransfer(
            walletLogEntry.Transfer.SubscriptionInitialPaymentCollected
          ),
        ),
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
          aliceValidator.participantClientWithAdminToken,
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
        aliceWallet.acceptSubscriptionRequest(request.subscriptionRequest.contractId),
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
          aliceValidator.participantClientWithAdminToken,
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
          { case logEntry: walletLogEntry.BalanceChange =>
            // Rejecting the accepted subscription request returned the 42CC locked coin.
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.SubscriptionInitialPaymentRejected
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          },
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.SubscriptionInitialPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-subscriptionPrice - smallAmount, -subscriptionPrice)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 100.0
            logEntry.coinPrice shouldBe coinPrice
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
          aliceValidator.participantClientWithAdminToken,
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

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWallet.acceptSubscriptionRequest(request.subscriptionRequest.contractId),
      )(
        "Request disappears from Alice's list",
        initPaymentCid => {
          aliceWallet.listSubscriptionRequests() shouldBe empty
          aliceWallet.listSubscriptionInitialPayments().find(_.contractId == initPaymentCid).value
        },
      )

      actAndCheck(
        "Charlie collects the initial payment",
        collectAcceptedSubscriptionRequest(
          aliceValidator.participantClientWithAdminToken,
          charlieUserId,
          charlieUserParty,
          aliceUserParty,
          initialPayment,
        ),
      )(
        "Charlie's balance reflects the collected payment",
        _ => charlieWallet.balance().unlockedQty should be > BigDecimal(40),
      )

      // Note: because paymentInterval == paymentDuration, the second payment can be made immediately
      val paymentCid = clue("Alice's automation triggers the second payment") {
        eventually() {
          inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
            sub.subscription.payload should equal(
              request.subscriptionRequest.payload.subscriptionData
            )
            inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
              state.payload.subscription shouldBe sub.subscription.contractId
              state.payload.payData should equal(request.subscriptionRequest.payload.payData)
              state.contractId
            }
          }
        }
      }

      actAndCheck(
        "Charlie rejects the second payment",
        rejectSubscriptionPayment(
          aliceValidator.participantClientWithAdminToken,
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
      def checkSubscriptionPaymentTransfer(
          transactionSubtype: TransactionSubtype
      ): CheckTxHistoryFn = { case logEntry: walletLogEntry.Transfer =>
        // This is the actual payment, transferring the unlocked coin to
        // the receivers
        logEntry.transactionSubtype shouldBe transactionSubtype
        inside(logEntry.sender) { case (sender, amount) =>
          sender shouldBe aliceUserParty.toProtoPrimitive
          amount shouldBe BigDecimal(0)
        }

        inside(logEntry.receivers) { case Seq((receiver, amount)) =>
          receiver shouldBe charlieUserParty.toProtoPrimitive
          amount shouldBe subscriptionPrice
        }

        logEntry.senderHoldingFees shouldBe BigDecimal(0)
        logEntry.coinPrice shouldBe coinPrice
      }

      checkTxHistory(
        aliceWallet,
        Seq[CheckTxHistoryFn](
          { case logEntry: walletLogEntry.BalanceChange =>
            // Rejecting the second payment returned the 42CC locked coin.
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.SubscriptionPaymentRejected
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.SubscriptionPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-subscriptionPrice - smallAmount, -subscriptionPrice)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          checkSubscriptionPaymentTransfer(
            walletLogEntry.Transfer.SubscriptionInitialPaymentCollected
          ),
          { case logEntry: walletLogEntry.Transfer =>
            // Accepting the self-payment request created a 42CC locked coin,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.SubscriptionInitialPaymentAccepted
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(-subscriptionPrice - smallAmount, -subscriptionPrice)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.coinPrice shouldBe coinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
            logEntry.amount shouldBe 100.0
            logEntry.coinPrice shouldBe coinPrice
          },
        ),
      )

      checkTxHistory(
        charlieWallet,
        Seq(
          checkSubscriptionPaymentTransfer(
            walletLogEntry.Transfer.SubscriptionInitialPaymentCollected
          )
        ),
      )
    }

    "handle failed automation (direct transfer)" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      val validatorTxLogBefore = aliceValidatorWallet.listTransactions(None, 1000)

      val (offerCid, _) =
        actAndCheck(
          "Alice creates transfer offer",
          aliceWallet.createTransferOffer(
            bobUserParty,
            100.0,
            "direct transfer test",
            CantonTimestamp.now().plus(Duration.ofMinutes(1)),
            UUID.randomUUID.toString,
          ),
        )(
          "Bob sees transfer offer",
          _ => bobWallet.listTransferOffers() should have length 1,
        )

      clue("Bob accepts transfer offer") {
        bobWallet.acceptTransferOffer(offerCid)
        // At this point, Alice's automation fails to complete the accepted offer
      }

      checkTxHistory(
        aliceWallet,
        Seq({ case logEntry: walletLogEntry.Notification =>
          logEntry.transactionSubtype shouldBe walletLogEntry.Notification.DirectTransferFailed
          logEntry.details should startWith("ITR_InsufficientFunds")
        }),
      )

      // Only Alice should see notification (note that aliceValidator is shared between tests)
      val validatorTxLogAfter = aliceValidatorWallet.listTransactions(None, 1000)
      validatorTxLogBefore should be(validatorTxLogAfter)
      checkTxHistory(bobWallet, Seq.empty)
    }

    "handle failed automation (app payment)" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      val ((_, reqCid, _), _) = actAndCheck(
        "Alice creates self-payment request",
        createSelfPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Alice sees the self-payment request",
        _ => aliceWallet.listAppPaymentRequests() should not be empty,
      )

      clue("Alice tries to accept the self-payment request and fails") {
        assertCommandFailsDueToInsufficientFunds(
          aliceWallet.acceptAppPaymentRequest(reqCid)
        )
      }

      // Accepting an app payment fails synchronously and should not include a notification
      checkTxHistory(aliceWallet, Seq.empty)
    }

    "handle failed automation (subscription initial payment)" in { implicit env =>
      val aliceUserId = aliceWallet.config.ledgerApiUser

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceUserId,
          aliceUserParty,
          charlieUserParty,
          charlieUserParty,
          paymentAmount(100.0, walletCodegen.Currency.CC),
          paymentInterval = Duration.ofMinutes(60),
          paymentDuration = Duration.ofMinutes(60),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWallet.listSubscriptionRequests().headOption.value,
      )

      clue("Alice tries to accept the request and fails") {
        assertCommandFailsDueToInsufficientFunds(
          aliceWallet.acceptSubscriptionRequest(request.subscriptionRequest.contractId)
        )
      }

      // Accepting a subscription fails synchronously and should not include a notification
      checkTxHistory(aliceWallet, Seq.empty)
    }

    "handle failed automation (subscription payment)" in { implicit env =>
      val aliceUserId = aliceWallet.config.ledgerApiUser
      val charlieUserId = charlieWallet.config.ledgerApiUser
      val validatorTxLogBefore = aliceValidatorWallet.listTransactions(None, 1000)

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)

      val subscriptionPrice = BigDecimal(75.0)

      clue("Alice taps just enough coins for one payment") {
        aliceWallet.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceUserId,
          aliceUserParty,
          charlieUserParty,
          charlieUserParty,
          paymentAmount(subscriptionPrice, walletCodegen.Currency.CC),
          paymentInterval = Duration.ofMillis(10),
          paymentDuration = Duration.ofMillis(10),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWallet.listSubscriptionRequests().headOption.value,
      )

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWallet.acceptSubscriptionRequest(request.subscriptionRequest.contractId),
      )(
        "Request disappears from Alice's list",
        initPaymentCid => {
          aliceWallet.listSubscriptionRequests() shouldBe empty
          inside(
            aliceWallet.listSubscriptionInitialPayments().find(_.contractId == initPaymentCid)
          ) { case Some(initPayment) =>
            initPayment
          }
        },
      )

      // Because paymentInterval == paymentDuration, the second payment can be made immediately.
      // Automation will attempt to make the payment at any time after collecting the accepted subscription request,
      // each of these attempts will fail due to insufficient funds, and automation will retry the payment forever.
      // Each failed attempt will produce one WARN and one ERROR log entry, which we need to suppress.
      // TODO(#2034): simplify this test once automation stops retrying forever
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          val (subscriptionResult, _) = actAndCheck(
            "Charlie collects the initial payment",
            collectAcceptedSubscriptionRequest(
              aliceValidator.participantClientWithAdminToken,
              charlieUserId,
              charlieUserParty,
              aliceUserParty,
              initialPayment,
            ),
          )(
            "Charlie's balance reflects the collected payment",
            _ => charlieWallet.balance().unlockedQty should be > (subscriptionPrice - smallAmount),
          )

          clue("Failure notification appears") {
            eventually() {
              aliceWallet.listTransactions(None, 100).size should be > 3
            }
          }

          actAndCheck(
            "Charlie expires the subscription because it has not been paid",
            // The subscription was created with a 10ms payment interval,
            // here we assume that at least 10ms have passed since the initial payment.
            expireUnpaidSubscription(
              aliceValidator.participantClientWithAdminToken,
              charlieUserId,
              charlieUserParty,
              subscriptionResult._2,
            ),
          )(
            "Alice doesn't see any subscription",
            _ => aliceWallet.listSubscriptions() shouldBe empty,
          )

          val entries = aliceWallet.listTransactions(None, 100)
          val notifications = entries.dropRight(3)
          forExactly(notifications.size - 1, notifications) {
            case logEntry: walletLogEntry.Notification =>
              logEntry.transactionSubtype shouldBe walletLogEntry.Notification.SubscriptionPaymentFailed
              logEntry.details should startWith("ITR_InsufficientFunds")
            case e => fail(s"Unexpected log entry $e")
          }
          forExactly(1, notifications) {
            case logEntry: walletLogEntry.Notification =>
              logEntry.transactionSubtype shouldBe walletLogEntry.Notification.SubscriptionExpired
              logEntry.details should startWith("Expired")
            case e => fail(s"Unexpected log entry $e")
          }

          inside(entries.takeRight(3)(0)) { case logEntry: walletLogEntry.Transfer =>
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.SubscriptionInitialPaymentCollected
          }
          inside(entries.takeRight(3)(1)) { case logEntry: walletLogEntry.Transfer =>
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.SubscriptionInitialPaymentAccepted
          }
          inside(entries.takeRight(3)(2)) { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
          }

          // Validator should not see any notification (note that aliceValidator is shared between tests)
          val validatorTxLogAfter = aliceValidatorWallet.listTransactions(None, 1000)
          validatorTxLogBefore should be(validatorTxLogAfter)

          // Charlie (the provider of the subscription) should see a notification
          checkTxHistory(
            charlieWallet,
            Seq(
              { case logEntry: walletLogEntry.Notification =>
                logEntry.transactionSubtype shouldBe walletLogEntry.Notification.SubscriptionExpired
              },
              { case logEntry: walletLogEntry.Transfer =>
                logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.SubscriptionInitialPaymentCollected
              },
            ),
          )
        },
        entries =>
          forEvery(entries)(entry =>
            (entry.message + entry.throwable.fold("")(_.getMessage)) should include(
              "Failed making subscription payment due to Daml exception"
            )
          ),
      )
    }

    // Time based to avoid SV reward collection kicking in.
    "handle unexpected events" in { implicit env =>
      val sv1UserId = sv1Wallet.config.ledgerApiUser
      val sv1UserParty = onboardWalletUser(sv1Wallet, sv1Validator)

      // Note: SV1 is reused between tests, ignore TxLog entries created by previous tests
      val previousEventId = sv1Wallet
        .listTransactions(None, 10000)
        .headOption
        .map(_.indexRecord.eventId)

      val coinAmount = BigDecimal(42)

      val coin = loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          createCoin(
            sv1Validator.participantClientWithAdminToken,
            sv1UserId,
            sv1UserParty,
            amount = coinAmount,
          )
        },
        logs =>
          inside(logs) { case Seq(log) =>
            log.errorMessage should include("Unexpected coin create event")
          },
      )

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          archiveCoin(sv1Validator.participantClientWithAdminToken, sv1UserId, sv1UserParty, coin)
        },
        logs =>
          inside(logs) { case Seq(log) =>
            log.errorMessage should include(
              "Unexpected coin archive event"
            )
          },
      )

      // The two above operations (bare create and bare archive of a coin) will not happen in practice.
      // The user wallet tx log parser should throw an exception when encountering these unexpected events,
      // which should:
      // - Log errors while ingesting the transactions above
      // - Generate "Unknown" log entries in the TxLog
      // - Log errors while reading the TxLog below (recovering the full entry from the index record also involves parsing)
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
        checkTxHistory(
          sv1Wallet,
          Seq(
            { case _: walletLogEntry.Unknown => succeed },
            { case _: walletLogEntry.Unknown => succeed },
          ),
          previousEventId,
          ignore = {
            case balanceChange: walletLogEntry.BalanceChange =>
              balanceChange.transactionSubtype == walletLogEntry.BalanceChange.SvRewardCollected
            case _ => false
          },
        ),
        entries => forAll(entries)(_.errorMessage should include("Failed to parse transaction")),
      )
    }
  }

  private def assertCommandFailsDueToInsufficientFunds[T](cmd: => Unit): Unit = {
    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
      assertThrows[CommandFailure](
        cmd
      ),
      entries => {
        forExactly(
          1,
          entries,
        )(
          _.errorMessage should include(
            "the coin operation failed with a Daml exception: COO_Error(ITR_InsufficientFunds"
          )
        )
      },
    )
  }
}
