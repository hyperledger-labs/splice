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
import com.digitalasset.canton.config.DbConfig
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
      .simpleTopology(this.getClass.getSimpleName)
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
      // Some tests use the splitwell app to generate multi-party payments
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
  }

  "A wallet" should {

    "handle tap" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Tap to get some coins") {
        aliceWalletClient.tap(11.0)
        aliceWalletClient.tap(12.0)
      }

      checkTxHistory(
        aliceWalletClient,
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
      val sv1UserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)

      clue("Mint to get some coins") {
        mintCoin(
          sv1ValidatorBackend.participantClientWithAdminToken,
          sv1UserParty,
          47.0,
        )
      }

      checkTxHistory(
        sv1WalletClient,
        Seq(
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Mint
            logEntry.amount shouldBe 47.0
            logEntry.coinPrice shouldBe coinPrice
          }
        ),
        ignore = {
          case balanceChange: walletLogEntry.BalanceChange =>
            balanceChange.transactionSubtype == walletLogEntry.BalanceChange.SvRewardCollected
          case _ => false
        },
      )
    }

    "handle collected self-payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Tap to get some coins") {
        aliceWalletClient.tap(10.0)
        aliceWalletClient.tap(20.0)
        aliceWalletClient.tap(30.0)
      }

      val ((_, reqCid, _), _) = actAndCheck(
        "Alice creates self-payment request",
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Alice sees the self-payment request",
        _ => aliceWalletClient.listAppPaymentRequests() should not be empty,
      )

      val (_, acceptedPayment) = actAndCheck(
        "Alice accepts the self-payment request",
        aliceWalletClient.acceptAppPaymentRequest(reqCid),
      )(
        "Payment request disappears from list",
        acceptedPaymentCid => {
          aliceWalletClient.listAppPaymentRequests() shouldBe empty
          aliceWalletClient.listAcceptedAppPayments().find(_.contractId == acceptedPaymentCid).value
        },
      )

      actAndCheck(
        "Alice collects self-payment request",
        collectAcceptedAppPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          Seq(aliceUserParty),
          acceptedPayment,
        ),
      )(
        "Accepted app payment disappears",
        _ => aliceWalletClient.listAcceptedAppPayments() shouldBe empty,
      )

      checkTxHistory(
        aliceWalletClient,
        Seq(
          { case logEntry: walletLogEntry.Transfer =>
            // Second part of collecting the payment: Transferring the coin to ourselves.
            logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.AppPaymentCollected
            inside(logEntry.sender) { case (sender, amount) =>
              sender shouldBe aliceUserParty.toProtoPrimitive
              amount should beWithin(selfPaymentAmount - smallAmount, selfPaymentAmount)
            }
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Tap to get some coins") {
        aliceWalletClient.tap(10.0)
        aliceWalletClient.tap(20.0)
        aliceWalletClient.tap(30.0)
      }

      val ((_, reqCid, _), _) = actAndCheck(
        "Alice creates self-payment request",
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Alice sees the self-payment request",
        _ => aliceWalletClient.listAppPaymentRequests() should not be empty,
      )

      val (acceptedPaymentCid, _) = actAndCheck(
        "Alice accepts the self-payment request",
        aliceWalletClient.acceptAppPaymentRequest(reqCid),
      )(
        "Payment request disappears from list",
        _ => aliceWalletClient.listAppPaymentRequests() shouldBe empty,
      )

      actAndCheck(
        "Alice rejects the self-payment request",
        rejectAcceptedAppPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          acceptedPaymentCid,
        ),
      )(
        "Accepted app payment disappears",
        _ => aliceWalletClient.listAcceptedAppPayments() shouldBe empty,
      )

      checkTxHistory(
        aliceWalletClient,
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val aliceValidatorUserParty = aliceValidatorBackend.getValidatorPartyId()

      // Based on Numeric to make sure divisions match Decimal computation in Daml.
      val transferAmountCC = Numeric.assertFromBigDecimal(scale, 22.0)
      val transferAmountUSD = Numeric.assertFromBigDecimal(scale, 20.0)
      val transferAmountUSDinCC = Numeric
        .divide(scale, transferAmountUSD, Numeric.assertFromBigDecimal(scale, coinPrice))
        .value
      val transferAmountTotalCC = transferAmountCC.add(transferAmountUSDinCC)

      clue("Tap to get some coins") {
        aliceWalletClient.tap(100.0)
      }

      val ((_, reqCid, _), _) = actAndCheck(
        "Alice creates payment request",
        createPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, transferAmountCC, walletCodegen.Currency.CC),
            receiverAmount(aliceValidatorUserParty, transferAmountUSD, walletCodegen.Currency.USD),
          ),
        ),
      )(
        "Alice sees the payment request",
        _ => aliceWalletClient.listAppPaymentRequests() should not be empty,
      )

      val (_, acceptedPayment) = actAndCheck(
        "Alice accepts the payment request",
        aliceWalletClient.acceptAppPaymentRequest(reqCid),
      )(
        "Payment request disappears from list",
        acceptedPaymentCid => {
          aliceWalletClient.listAppPaymentRequests() shouldBe empty
          aliceWalletClient.listAcceptedAppPayments().find(_.contractId == acceptedPaymentCid).value
        },
      )

      actAndCheck(
        "Receivers collect the payment request",
        collectAcceptedAppPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          Seq(aliceUserParty, charlieUserParty, aliceValidatorUserParty),
          acceptedPayment,
        ),
      )(
        "Accepted app payment disappears",
        _ => aliceWalletClient.listAcceptedAppPayments() shouldBe empty,
      )

      checkTxHistory(
        aliceWalletClient,
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      val transferAmount = 32.0

      clue("Alice taps some coins") {
        aliceWalletClient.tap(100.0)
      }

      val (offerCid, _) =
        actAndCheck(
          "Alice creates transfer offer",
          aliceWalletClient.createTransferOffer(
            bobUserParty,
            transferAmount,
            "direct transfer test",
            CantonTimestamp.now().plus(Duration.ofMinutes(1)),
            UUID.randomUUID.toString,
          ),
        )("Bob sees transfer offer", _ => bobWalletClient.listTransferOffers() should have length 1)

      actAndCheck("Bob accepts transfer offer", bobWalletClient.acceptTransferOffer(offerCid))(
        "Alice does not see transfer offer anymore",
        _ => aliceWalletClient.listTransferOffers() shouldBe empty,
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

        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.coinPrice shouldBe coinPrice
      }

      checkTxHistory(
        aliceWalletClient,
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
        bobWalletClient,
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
        charlieSplitwellClient.acceptInvite(invite),
      )(
        "Alice sees the accepted invite",
        _ => aliceSplitwellClient.listAcceptedGroupInvites(key.id) should not be empty,
      )

      actAndCheck(
        "Alice joins all accepted invites",
        aliceSplitwellClient
          .listAcceptedGroupInvites(key.id)
          .foreach(accepted => aliceSplitwellClient.joinGroup(accepted.contractId)),
      )(
        "Charlie sees the group and Alice doesn't see any accepted invite",
        _ => {
          charlieSplitwellClient.listGroups() should have size 1
          aliceSplitwellClient.listAcceptedGroupInvites(key.id) should be(empty)
        },
      )

      actAndCheck("Alice taps some coins", aliceWalletClient.tap(100.0))(
        "Alice has some coins",
        _ => aliceWalletClient.balance().unlockedQty should be > BigDecimal(0),
      )

      val (_, paymentRequest) = actAndCheck(
        "Alice initiates a transfer of CC to all other group members",
        aliceSplitwellClient.initiateTransfer(
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
        _ => aliceWalletClient.listAppPaymentRequests().headOption.value,
      )

      actAndCheck(
        "Alice confirms the payment request",
        aliceWalletClient.acceptAppPaymentRequest(paymentRequest.appPaymentRequest.contractId),
      )(
        "All parties see new balances",
        _ => {
          aliceWalletClient.listAcceptedAppPayments() shouldBe empty
          bobWalletClient.balance().unlockedQty should be > BigDecimal(0.0)
          charlieWalletClient.balance().unlockedQty should be > BigDecimal(0.0)
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

        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.coinPrice shouldBe coinPrice
      }

      checkTxHistory(
        aliceWalletClient,
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
        bobWalletClient,
        Seq(checkTransfer),
      )

      checkTxHistory(
        charlieWalletClient,
        Seq(checkTransfer),
      )
    }

    "handle collected subscription payments" in { implicit env =>
      val aliceUserId = aliceWalletClient.config.ledgerApiUser
      val charlieUserId = charlieWalletClient.config.ledgerApiUser

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      val subscriptionPrice = 42.0

      clue("Alice taps some coins") {
        aliceWalletClient.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
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
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWalletClient.acceptSubscriptionRequest(request.subscriptionRequest.contractId),
      )(
        "Request disappears from Alice's list",
        initPaymentCid => {
          aliceWalletClient.listSubscriptionRequests() shouldBe empty
          aliceWalletClient
            .listSubscriptionInitialPayments()
            .find(_.contractId == initPaymentCid)
            .value
        },
      )

      actAndCheck(
        "Charlie collects the initial payment",
        collectAcceptedSubscriptionRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          charlieUserId,
          charlieUserParty,
          aliceUserParty,
          initialPayment,
        ),
      )(
        "Charlie's balance reflects the collected payment",
        _ => charlieWalletClient.balance().unlockedQty should be > BigDecimal(40),
      )

      // Note: because paymentInterval == paymentDuration, the second payment can be made immediately
      val payment = clue("Alice's automation triggers the second payment") {
        eventually() {
          inside(aliceWalletClient.listSubscriptions()) { case Seq(sub) =>
            sub.subscription.payload should equal(
              request.subscriptionRequest.payload.subscriptionData
            )
            inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
              state.payload.subscription shouldBe sub.subscription.contractId
              state.payload.payData should equal(request.subscriptionRequest.payload.payData)
              state
            }
          }
        }
      }

      actAndCheck(
        "Charlie collects the second payment",
        collectSubscriptionPayment(
          aliceValidatorBackend.participantClientWithAdminToken,
          charlieUserId,
          charlieUserParty,
          aliceUserParty,
          payment,
        ),
      )(
        "Charlie's balance reflects the collected payment",
        _ => charlieWalletClient.balance().unlockedQty should be > BigDecimal(80),
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

        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.coinPrice shouldBe coinPrice
      }

      checkTxHistory(
        aliceWalletClient,
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
        charlieWalletClient,
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
      val aliceUserId = aliceWalletClient.config.ledgerApiUser
      val charlieUserId = charlieWalletClient.config.ledgerApiUser

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      val subscriptionPrice = 42.0

      clue("Alice taps some coins") {
        aliceWalletClient.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
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
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      val (initialPaymentCid, _) = actAndCheck(
        "Alice accepts the request",
        aliceWalletClient.acceptSubscriptionRequest(request.subscriptionRequest.contractId),
      )(
        "Request disappears from Alice's list",
        _ => {
          charlieWalletClient.listSubscriptionInitialPayments()
          aliceWalletClient.listSubscriptionRequests() shouldBe empty
        },
      )

      actAndCheck(
        "Charlie rejects the initial payment",
        rejectAcceptedSubscriptionRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          charlieUserId,
          charlieUserParty,
          initialPaymentCid,
        ),
      )(
        "Alice's balance reflects the returned locked coin",
        _ => aliceWalletClient.balance().unlockedQty should be > (BigDecimal(100) - smallAmount),
      )

      checkTxHistory(
        aliceWalletClient,
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
        charlieWalletClient,
        Seq.empty,
      )
    }

    "handle rejected subscription payments" in { implicit env =>
      val aliceUserId = aliceWalletClient.config.ledgerApiUser
      val charlieUserId = charlieWalletClient.config.ledgerApiUser

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      val subscriptionPrice = BigDecimal(42.0)

      clue("Alice taps some coins") {
        aliceWalletClient.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
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
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWalletClient.acceptSubscriptionRequest(request.subscriptionRequest.contractId),
      )(
        "Request disappears from Alice's list",
        initPaymentCid => {
          aliceWalletClient.listSubscriptionRequests() shouldBe empty
          aliceWalletClient
            .listSubscriptionInitialPayments()
            .find(_.contractId == initPaymentCid)
            .value
        },
      )

      actAndCheck(
        "Charlie collects the initial payment",
        collectAcceptedSubscriptionRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          charlieUserId,
          charlieUserParty,
          aliceUserParty,
          initialPayment,
        ),
      )(
        "Charlie's balance reflects the collected payment",
        _ => charlieWalletClient.balance().unlockedQty should be > BigDecimal(40),
      )

      // Note: because paymentInterval == paymentDuration, the second payment can be made immediately
      val paymentCid = clue("Alice's automation triggers the second payment") {
        eventually() {
          inside(aliceWalletClient.listSubscriptions()) { case Seq(sub) =>
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
          aliceValidatorBackend.participantClientWithAdminToken,
          charlieUserId,
          charlieUserParty,
          paymentCid,
        ),
      )(
        "Alice's balance reflects the returned locked coin",
        _ =>
          aliceWalletClient.balance().unlockedQty should be > (BigDecimal(
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

        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.coinPrice shouldBe coinPrice
      }

      checkTxHistory(
        aliceWalletClient,
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
            // Depending on timing we may have incurred holding fees at this point.
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
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
        charlieWalletClient,
        Seq(
          checkSubscriptionPaymentTransfer(
            walletLogEntry.Transfer.SubscriptionInitialPaymentCollected
          )
        ),
      )
    }

    "handle failed automation (direct transfer)" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val validatorTxLogBefore = aliceValidatorWalletClient.listTransactions(None, 1000)

      val (offerCid, _) =
        actAndCheck(
          "Alice creates transfer offer",
          aliceWalletClient.createTransferOffer(
            bobUserParty,
            100.0,
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
    }

    "handle failed automation (app payment)" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      val ((_, reqCid, _), _) = actAndCheck(
        "Alice creates self-payment request",
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Alice sees the self-payment request",
        _ => aliceWalletClient.listAppPaymentRequests() should not be empty,
      )

      clue("Alice tries to accept the self-payment request and fails") {
        assertCommandFailsDueToInsufficientFunds(
          aliceWalletClient.acceptAppPaymentRequest(reqCid)
        )
      }

      // Accepting an app payment fails synchronously and should not include a notification
      checkTxHistory(aliceWalletClient, Seq.empty)
    }

    "handle failed automation (subscription initial payment)" in { implicit env =>
      val aliceUserId = aliceWalletClient.config.ledgerApiUser

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
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
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      clue("Alice tries to accept the request and fails") {
        assertCommandFailsDueToInsufficientFunds(
          aliceWalletClient.acceptSubscriptionRequest(request.subscriptionRequest.contractId)
        )
      }

      // Accepting a subscription fails synchronously and should not include a notification
      checkTxHistory(aliceWalletClient, Seq.empty)
    }

    "handle failed automation (subscription payment)" in { implicit env =>
      val aliceUserId = aliceWalletClient.config.ledgerApiUser
      val charlieUserId = charlieWalletClient.config.ledgerApiUser
      val validatorTxLogBefore = aliceValidatorWalletClient.listTransactions(None, 1000)

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      val subscriptionPrice = BigDecimal(75.0)

      clue("Alice taps just enough coins for one payment") {
        aliceWalletClient.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Create subscription request (Alice subscribing to Charlie's service)",
        createSubscriptionRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
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
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWalletClient.acceptSubscriptionRequest(request.subscriptionRequest.contractId),
      )(
        "Request disappears from Alice's list",
        initPaymentCid => {
          aliceWalletClient.listSubscriptionRequests() shouldBe empty
          inside(
            aliceWalletClient.listSubscriptionInitialPayments().find(_.contractId == initPaymentCid)
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
              aliceValidatorBackend.participantClientWithAdminToken,
              charlieUserId,
              charlieUserParty,
              aliceUserParty,
              initialPayment,
            ),
          )(
            "Charlie's balance reflects the collected payment",
            _ =>
              charlieWalletClient
                .balance()
                .unlockedQty should be > (subscriptionPrice - smallAmount),
          )

          clue("Failure notification appears") {
            eventually() {
              aliceWalletClient.listTransactions(None, 100).size should be > 3
            }
          }

          actAndCheck(
            "Charlie expires the subscription because it has not been paid",
            // The subscription was created with a 10ms payment interval,
            // here we assume that at least 10ms have passed since the initial payment.
            expireUnpaidSubscription(
              aliceValidatorBackend.participantClientWithAdminToken,
              charlieUserId,
              charlieUserParty,
              subscriptionResult._2,
            ),
          )(
            "Alice doesn't see any subscription",
            _ => aliceWalletClient.listSubscriptions() shouldBe empty,
          )

          val entries = aliceWalletClient.listTransactions(None, 100)
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
          val validatorTxLogAfter = aliceValidatorWalletClient.listTransactions(None, 1000)
          validatorTxLogBefore should be(validatorTxLogAfter)

          // Charlie (the provider of the subscription) should see a notification
          checkTxHistory(
            charlieWalletClient,
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
      val sv1UserId = sv1WalletClient.config.ledgerApiUser
      val sv1UserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)

      // Note: SV1 is reused between tests, ignore TxLog entries created by previous tests
      val previousEventId = sv1WalletClient
        .listTransactions(None, 10000)
        .headOption
        .map(_.indexRecord.eventId)

      val coinAmount = BigDecimal(42)
      // TODO(#6480) cleanup expecting unexpected error messages in logs as a workaround
      val coin = loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          createCoin(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserId,
            sv1UserParty,
            amount = coinAmount,
          )
        },
        logs =>
          inside(logs) {
            case logLines if logLines.nonEmpty =>
              logLines.foreach(_.errorMessage should include("Unexpected coin create event"))
              logLines
                .filter(_.errorMessage contains ("RuntimeException"))
                .foreach(_.errorMessage should include("Unexpected coin create event"))
              logLines should have size (env.scans.local.size.toLong + 1) // + 1 for UserWalletTxLog
          },
      )

      // TODO(#6480) cleanup expecting unexpected error messages in logs as a workaround
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          archiveCoin(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserId,
            sv1UserParty,
            coin,
          )
        },
        logs =>
          inside(logs) {
            case logLines if logLines.nonEmpty =>
              logLines.foreach(_.errorMessage should include("Unexpected coin archive event"))
              logLines
                .filter(_.errorMessage contains ("RuntimeException"))
                .foreach(_.errorMessage should include("Unexpected coin archive event"))
              logLines should have size (env.scans.local.size.toLong + 1) // + 1 for UserWalletTxLog
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
          sv1WalletClient,
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

    "handle initial ACS" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Tap to get some coins") {
        aliceWalletClient.tap(11.0)
        aliceWalletClient.tap(12.0)
      }

      clue("Simulate network restart") {
        assert(
          !aliceValidatorBackend.config.storage.isInstanceOf[DbConfig],
          "This test only makes sense if the validator node doesn't remember anything between restarts",
        )
        aliceValidatorBackend.stop()
        aliceValidatorBackend.start()
        aliceValidatorBackend.waitForInitialization()
      }

      // Arbitrary coin price assigned by the user wallet tx log parser
      val unknownCoinPrice = BigDecimal(1)

      checkTxHistory(
        aliceWalletClient,
        Seq(
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Mint
            logEntry.amount shouldBe 12.0
            logEntry.coinPrice shouldBe unknownCoinPrice
          },
          { case logEntry: walletLogEntry.BalanceChange =>
            logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Mint
            logEntry.amount shouldBe 11.0
            logEntry.coinPrice shouldBe unknownCoinPrice
          },
        ),
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
