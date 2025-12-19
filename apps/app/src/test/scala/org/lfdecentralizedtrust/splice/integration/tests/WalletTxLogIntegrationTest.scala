package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.daml.lf.data.Numeric
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as subsCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.store.{Limit, PageLimit}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AnsSubscriptionRenewalPaymentTrigger
import org.lfdecentralizedtrust.splice.sv.config.InitialAnsConfig
import org.lfdecentralizedtrust.splice.util.{
  SpliceUtil,
  SplitwellTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import org.lfdecentralizedtrust.splice.wallet.automation.AcceptedTransferOfferTrigger
import org.lfdecentralizedtrust.splice.validator.automation.RenewTransferPreapprovalTrigger
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  NotificationTxLogEntry,
  TransferTxLogEntry,
  UnknownTxLogEntry,
  TxLogEntry as walletLogEntry,
}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import scala.jdk.OptionConverters.*
import monocle.macros.syntax.lens.*

import java.math.RoundingMode
import java.time.Duration
import java.util.UUID

class WalletTxLogIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil
    with ExternallySignedPartyTestUtil
    with TriggerTestUtil {

  private val amuletPrice = BigDecimal(0.75).setScale(10)

  override lazy val sanityChecksIgnoredRootCreates = Seq(
    amuletCodegen.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  override lazy val sanityChecksIgnoredRootExercises = Seq(
    (amuletCodegen.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID, "Archive")
  )

  private def usdAsTappedAmulet(usd: Double) =
    BigDecimal(BigDecimal(usd).bigDecimal.divide(amuletPrice.bigDecimal, 10, RoundingMode.CEILING))

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // The wallet automation periodically merges amulets, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      // Set a non-unit amulet price to better test CC-USD conversion.
      .addConfigTransform((_, config) => ConfigTransforms.setAmuletPrice(amuletPrice)(config))
      .addConfigTransform((_, config) =>
        // setting the initialAnsEntryLifetime to be the same as initialAnsRenewalDuration
        ConfigTransforms
          .updateAllSvAppFoundDsoConfigs_(
            _.copy(
              initialAnsConfig = InitialAnsConfig(
                renewalDuration = NonNegativeFiniteDuration.ofDays(30),
                entryLifetime = NonNegativeFiniteDuration.ofDays(30),
              )
            )
          )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms
          .updateAllValidatorConfigs_(
            // set a large pre-approval duration to ensure that the fees paid are not so small
            // as to be a rounding error in the test.
            _.focus(_.transferPreapproval.preapprovalLifetime)
              .replace(NonNegativeFiniteDuration.ofDays(10 * 365))
              // set renewal duration to be same as pre-approval lifetime to ensure renewal gets
              // triggered immediately
              .focus(_.transferPreapproval.renewalDuration)
              .replace(NonNegativeFiniteDuration.ofDays(10 * 365))
          )(config)
      )
      // Some tests use the splitwell app to generate multi-party payments
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
  }

  "A wallet" should {

    "handle tap" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(11.0)
        aliceWalletClient.tap(12.0)
      }

      checkTxHistory(
        aliceWalletClient,
        Seq(
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(12.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(11.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
        ),
      )
    }

    "handle mint" in { implicit env =>
      val sv1UserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)

      clue("Mint to get some amulets") {
        mintAmulet(
          sv1ValidatorBackend.participantClientWithAdminToken,
          sv1UserParty,
          47.0,
        )
      }

      checkTxHistory(
        sv1WalletClient,
        Seq(
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Mint.toProto
            logEntry.amount shouldBe 47.0
            logEntry.amuletPrice shouldBe amuletPrice
          }
        ),
        trafficTopups = IgnoreTopupsDevNet,
        ignore = {
          case balanceChange: BalanceChangeTxLogEntry =>
            balanceChange.subtype.value == walletLogEntry.TransferTransactionSubtype.WalletAutomation.toProto
          case _ => false
        },
      )
    }

    "handle collected self-payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(10.0)
        aliceWalletClient.tap(20.0)
        aliceWalletClient.tap(30.0)
      }

      val ((reqCid, _), _) = actAndCheck(
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
          { case logEntry: TransferTxLogEntry =>
            // Second part of collecting the payment: Transferring the amulet to ourselves.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.AppPaymentCollected.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              selfPaymentAmount - smallAmount,
              selfPaymentAmount,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: TransferTxLogEntry =>
            // Accepting the self-payment request created a 10CC locked amulet,
            // leading to a net loss of slightly over 10CC because of fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.AppPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -selfPaymentAmount - smallAmount,
              -selfPaymentAmount,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(30.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(20.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(10.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
        ),
      )
    }

    "handle rejected self-payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(10.0)
        aliceWalletClient.tap(20.0)
        aliceWalletClient.tap(30.0)
      }

      val ((reqCid, _), _) = actAndCheck(
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
          { case logEntry: BalanceChangeTxLogEntry =>
            // Rejecting the accepted self-payment request returned the 10CC locked amulet.
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.AppPaymentRejected.toProto
            logEntry.amount should beWithin(selfPaymentAmount, selfPaymentAmount + smallAmount)
          },
          { case logEntry: TransferTxLogEntry =>
            // Accepting the self-payment request created a 10CC locked amulet,
            // leading to a net loss of slightly over 10CC because of transfer fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.AppPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -selfPaymentAmount - smallAmount,
              -selfPaymentAmount,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(30.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(20.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(10.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
        ),
      )
    }

    "handle mixed unit payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val aliceValidatorUserParty = aliceValidatorBackend.getValidatorPartyId()

      // Based on Numeric to make sure divisions match Decimal computation in Daml.
      val transferAmountAmulet = Numeric.assertFromBigDecimal(scale, 22.0)
      val transferAmountUSD = Numeric.assertFromBigDecimal(scale, 20.0)
      val transferAmountUSDinAmulet = Numeric
        .divide(scale, transferAmountUSD, Numeric.assertFromBigDecimal(scale, amuletPrice))
        .value
      val transferAmountTotalCC = transferAmountAmulet.add(transferAmountUSDinAmulet)

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(100.0)
      }

      val ((reqCid, _), _) = actAndCheck(
        "Alice creates payment request",
        createPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          Seq(
            receiverAmount(charlieUserParty, transferAmountAmulet, walletCodegen.Unit.AMULETUNIT),
            receiverAmount(aliceValidatorUserParty, transferAmountUSD, walletCodegen.Unit.USDUNIT),
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
          { case logEntry: TransferTxLogEntry =>
            // Collecting the payment: Transferring the amulet to the receivers
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.AppPaymentCollected.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount shouldBe BigDecimal(0)
            inside(logEntry.receivers) { case Seq(receiver1, receiver2) =>
              receiver1.party shouldBe charlieUserParty.toProtoPrimitive
              receiver1.amount should beWithin(
                BigDecimal(transferAmountAmulet) - smallAmount,
                transferAmountAmulet,
              )

              receiver2.party shouldBe aliceValidatorUserParty.toProtoPrimitive
              receiver2.amount shouldBe BigDecimal(transferAmountUSDinAmulet)
            }
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: TransferTxLogEntry =>
            // Accepting the payment request created a locked amulet,
            // leading to a net loss because of transfer fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.AppPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -BigDecimal(transferAmountTotalCC) - smallAmount,
              -BigDecimal(transferAmountTotalCC),
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(100.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
        ),
      )
    }

    "handle completed transfer offers" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      val transferAmount = 32.0

      clue("Alice taps some amulets") {
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
      val checkTransfer: CheckTxHistoryFn = { case logEntry: TransferTxLogEntry =>
        logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.P2PPaymentCompleted.toProto
        logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
        logEntry.sender.value.amount should beWithin(-transferAmount - smallAmount, -transferAmount)

        inside(logEntry.receivers) { case Seq(receiver) =>
          receiver.party shouldBe bobUserParty.toProtoPrimitive
          receiver.amount shouldBe transferAmount
        }

        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.amuletPrice shouldBe amuletPrice
      }

      checkTxHistory(
        aliceWalletClient,
        Seq(
          checkTransfer,
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(100.0)
            logEntry.amuletPrice shouldBe amuletPrice
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

      actAndCheck("Alice taps some amulets", aliceWalletClient.tap(100.0))(
        "Alice has some amulets",
        _ => aliceWalletClient.balance().unlockedQty should be > BigDecimal(0),
      )

      actAndCheck(
        "Alice settles her CC debts with all other group members",
        splitwellTransfer(
          aliceSplitwellClient,
          aliceWalletClient,
          key,
          Seq(
            new walletCodegen.ReceiverAmuletAmount(
              bobUserParty.toProtoPrimitive,
              new java.math.BigDecimal(20.0),
            ),
            new walletCodegen.ReceiverAmuletAmount(
              charlieUserParty.toProtoPrimitive,
              new java.math.BigDecimal(30.0),
            ),
          ),
        ),
      )(
        "All parties see the new balances",
        _ => {
          aliceWalletClient.listAcceptedAppPayments() shouldBe empty
          bobWalletClient.balance().unlockedQty should be > BigDecimal(0.0)
          charlieWalletClient.balance().unlockedQty should be > BigDecimal(0.0)
        },
      )

      // All parties see the same representation of the transfer
      val checkTransfer: CheckTxHistoryFn = { case logEntry: TransferTxLogEntry =>
        // This is the actual payment, transferring the unlocked amulet to
        // the receivers
        logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.AppPaymentCollected.toProto
        logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
        logEntry.sender.value.amount shouldBe BigDecimal(0)

        inside(logEntry.receivers) { case Seq(receiver1, receiver2) =>
          receiver1.party shouldBe bobUserParty.toProtoPrimitive
          receiver1.amount shouldBe 20

          receiver2.party shouldBe charlieUserParty.toProtoPrimitive
          receiver2.amount shouldBe 30
        }

        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.amuletPrice shouldBe amuletPrice
      }

      checkTxHistory(
        aliceWalletClient,
        Seq[CheckTxHistoryFn](
          checkTransfer,
          { case logEntry: TransferTxLogEntry =>
            // Accepting the self-payment request created a 50CC locked amulet,
            // leading to a net loss of slightly over 50CC because of transfer fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.AppPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(-50 - smallAmount, -50)
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(100.0)
            logEntry.amuletPrice shouldBe amuletPrice
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

      clue("Alice taps some amulets") {
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
          paymentAmount(subscriptionPrice, walletCodegen.Unit.AMULETUNIT),
          paymentInterval = Duration.ofMinutes(60),
          paymentDuration = Duration.ofMinutes(60),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWalletClient.acceptSubscriptionRequest(request.contractId),
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
              new subsCodegen.Subscription(
                request.payload.subscriptionData,
                request.contractId,
              )
            )
            inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
              state.payload.subscription shouldBe sub.subscription.contractId
              state.payload.payData should equal(request.payload.payData)
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
          transactionSubtype: walletLogEntry.TransferTransactionSubtype
      ): CheckTxHistoryFn = { case logEntry: TransferTxLogEntry =>
        // This is the actual payment, transferring the unlocked amulet to
        // the receivers
        logEntry.subtype.value shouldBe transactionSubtype.toProto
        logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
        logEntry.sender.value.amount shouldBe BigDecimal(0)

        inside(logEntry.receivers) { case Seq(receiver) =>
          receiver.party shouldBe charlieUserParty.toProtoPrimitive
          receiver.amount shouldBe subscriptionPrice
        }

        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.amuletPrice shouldBe amuletPrice
      }

      checkTxHistory(
        aliceWalletClient,
        Seq[CheckTxHistoryFn](
          checkSubscriptionPaymentTransfer(
            walletLogEntry.TransferTransactionSubtype.SubscriptionPaymentCollected
          ),
          { case logEntry: TransferTxLogEntry =>
            // Accepting the self-payment request created a 42CC locked amulet,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -subscriptionPrice - smallAmount,
              -subscriptionPrice,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          checkSubscriptionPaymentTransfer(
            walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentCollected
          ),
          { case logEntry: TransferTxLogEntry =>
            // Accepting the self-payment request created a 42CC locked amulet,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -subscriptionPrice - smallAmount,
              -subscriptionPrice,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(100.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
        ),
      )

      checkTxHistory(
        charlieWalletClient,
        Seq(
          checkSubscriptionPaymentTransfer(
            walletLogEntry.TransferTransactionSubtype.SubscriptionPaymentCollected
          ),
          checkSubscriptionPaymentTransfer(
            walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentCollected
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

      clue("Alice taps some amulets") {
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
          paymentAmount(subscriptionPrice, walletCodegen.Unit.AMULETUNIT),
          paymentInterval = Duration.ofMinutes(60),
          paymentDuration = Duration.ofMinutes(60),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      val (initialPaymentCid, _) = actAndCheck(
        "Alice accepts the request",
        aliceWalletClient.acceptSubscriptionRequest(request.contractId),
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
        "Alice's balance reflects the returned locked amulet",
        _ => aliceWalletClient.balance().unlockedQty should be > (BigDecimal(100) - smallAmount),
      )

      checkTxHistory(
        aliceWalletClient,
        Seq[CheckTxHistoryFn](
          { case logEntry: BalanceChangeTxLogEntry =>
            // Rejecting the accepted subscription request returned the 42CC locked amulet.
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.SubscriptionInitialPaymentRejected.toProto
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
          },
          { case logEntry: TransferTxLogEntry =>
            // Accepting the self-payment request created a 42CC locked amulet,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -subscriptionPrice - smallAmount,
              -subscriptionPrice,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(100.0)
            logEntry.amuletPrice shouldBe amuletPrice
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

      clue("Alice taps some amulets") {
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
          paymentAmount(subscriptionPrice, walletCodegen.Unit.AMULETUNIT),
          paymentInterval = Duration.ofMinutes(60),
          paymentDuration = Duration.ofMinutes(60),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWalletClient.acceptSubscriptionRequest(request.contractId),
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
              new subsCodegen.Subscription(
                request.payload.subscriptionData,
                request.contractId,
              )
            )
            inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
              state.payload.subscription shouldBe sub.subscription.contractId
              state.payload.payData should equal(request.payload.payData)
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
        "Alice's balance reflects the returned locked amulet",
        _ =>
          aliceWalletClient.balance().unlockedQty should be > (BigDecimal(
            100
          ) - subscriptionPrice - smallAmount),
      )

      // All parties see the same representation of the transfer
      def checkSubscriptionPaymentTransfer(
          transactionSubtype: walletLogEntry.TransferTransactionSubtype
      ): CheckTxHistoryFn = { case logEntry: TransferTxLogEntry =>
        // This is the actual payment, transferring the unlocked amulet to
        // the receivers
        logEntry.subtype.value shouldBe transactionSubtype.toProto
        logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
        logEntry.sender.value.amount shouldBe BigDecimal(0)

        inside(logEntry.receivers) { case Seq(receiver) =>
          receiver.party shouldBe charlieUserParty.toProtoPrimitive
          receiver.amount shouldBe subscriptionPrice
        }

        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.amuletPrice shouldBe amuletPrice
      }

      checkTxHistory(
        aliceWalletClient,
        Seq[CheckTxHistoryFn](
          { case logEntry: BalanceChangeTxLogEntry =>
            // Rejecting the second payment returned the 42CC locked amulet.
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.SubscriptionPaymentRejected.toProto
            logEntry.amount should beWithin(subscriptionPrice, subscriptionPrice + smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: TransferTxLogEntry =>
            // Accepting the self-payment request created a 42CC locked amulet,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -subscriptionPrice - smallAmount,
              -subscriptionPrice,
            )
            logEntry.receivers shouldBe empty
            // Depending on timing we may have incurred holding fees at this point.
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          checkSubscriptionPaymentTransfer(
            walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentCollected
          ),
          { case logEntry: TransferTxLogEntry =>
            // Accepting the self-payment request created a 42CC locked amulet,
            // leading to a net loss of slightly over 42CC because of transfer fees.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -subscriptionPrice - smallAmount,
              -subscriptionPrice,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(100.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
        ),
      )

      checkTxHistory(
        charlieWalletClient,
        Seq(
          checkSubscriptionPaymentTransfer(
            walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentCollected
          )
        ),
      )
    }

    "handle collected ans subscription payments" in { implicit env =>
      val aliceUserId = aliceWalletClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val subscriptionPrice = 1.0

      clue("Alice taps some amulets") {
        aliceWalletClient.tap(100.0)
      }

      val (_, request) = actAndCheck(
        "Request ans entry",
        requestAnsEntry(
          aliceValidatorBackend.participantClientWithAdminToken,
          s"alice.unverified.$ansAcronym",
          aliceUserId,
          aliceUserParty,
        ),
      )(
        "Request appears in Alice's wallet",
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      // Pause trigger so that we can actuall observe the SubscriptionPayment state before the SVs collect the payment.
      setTriggersWithin(triggersToPauseAtStart =
        Seq(sv1Backend.dsoDelegateBasedAutomation.trigger[AnsSubscriptionRenewalPaymentTrigger])
      ) {

        actAndCheck(
          "Alice accepts the request",
          aliceWalletClient.acceptSubscriptionRequest(request.contractId),
        )(
          "Request disappears from Alice's list",
          _ => {
            aliceWalletClient.listSubscriptionRequests() shouldBe empty
            aliceWalletClient
              .listSubscriptions()
              .find(
                _.subscription.payload.reference == request.contractId
              )
              .value
          },
        )

        // Note: because paymentInterval == paymentDuration, the second payment can be made immediately
        clue("Alice's automation triggers the second payment") {
          eventually() {
            inside(aliceWalletClient.listSubscriptions()) { case Seq(sub) =>
              sub.subscription.payload should equal(
                new subsCodegen.Subscription(
                  request.payload.subscriptionData,
                  request.contractId,
                )
              )

              inside(sub.state) { case HttpWalletAppClient.SubscriptionPayment(state) =>
                state.payload.subscription shouldBe sub.subscription.contractId
                state.payload.payData should equal(request.payload.payData)
                state
              }
            }
          }
        }
      }

      checkTxHistory(
        aliceWalletClient,
        Seq[CheckTxHistoryFn](
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.EntryRenewalPaymentCollection.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount shouldBe BigDecimal(0)

            inside(logEntry.receivers) { case Seq(receiver) =>
              receiver.party shouldBe dsoParty.toProtoPrimitive
              // amulet received by dso is burnt
              receiver.amount shouldBe BigDecimal(0)
            }

            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -subscriptionPrice - smallAmount,
              -subscriptionPrice,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.InitialEntryPaymentCollection.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount shouldBe BigDecimal(0)

            inside(logEntry.receivers) { case Seq(receiver) =>
              receiver.party shouldBe dsoParty.toProtoPrimitive
              // amulet received by dso is burnt
              receiver.amount shouldBe BigDecimal(0)
            }

            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentAccepted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(
              -subscriptionPrice - smallAmount,
              -subscriptionPrice,
            )
            logEntry.receivers shouldBe empty
            logEntry.senderHoldingFees should beWithin(0, smallAmount)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
            logEntry.amount shouldBe usdAsTappedAmulet(100.0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
        ),
      )
    }

    "handle external party transfer preapprovals" in { implicit env =>
      def renewTransferPreapprovalTrigger =
        bobValidatorBackend.validatorAutomation.trigger[RenewTransferPreapprovalTrigger]

      val amuletConfig = sv1ScanBackend.getAmuletConfigAsOf(env.environment.clock.now)
      val preapprovalFeeRate = amuletConfig.transferPreapprovalFee.toScala.map(BigDecimal(_))
      val (_, preapprovalFee) = SpliceUtil.transferPreapprovalFees(
        bobValidatorBackend.config.transferPreapproval.preapprovalLifetime,
        preapprovalFeeRate,
        amuletPrice,
      )
      val creationTxLog: CheckTxHistoryFn = { case logEntry: TransferTxLogEntry =>
        logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.TransferPreapprovalCreation.toProto
        logEntry.sender.value.party shouldBe bobValidatorBackend
          .getValidatorPartyId()
          .toProtoPrimitive
        logEntry.sender.value.amount should beWithin(
          -preapprovalFee - smallAmount,
          -preapprovalFee,
        )
        logEntry.receivers shouldBe empty
        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.amuletPrice shouldBe amuletPrice
      }
      val tapTxLog: CheckTxHistoryFn = { case logEntry: BalanceChangeTxLogEntry =>
        logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
      }
      bobValidatorWalletClient.tap(30.0)

      val onboarding = onboardExternalParty(bobValidatorBackend)

      val initialCid = setTriggersWithin(
        triggersToPauseAtStart = Seq(renewTransferPreapprovalTrigger),
        triggersToResumeAtStart = Seq.empty,
      ) {
        val preapprovalCid =
          createAndAcceptExternalPartySetupProposal(bobValidatorBackend, onboarding)
        checkTxHistory(
          bobValidatorWalletClient,
          Seq(creationTxLog, tapTxLog),
          trafficTopups = IgnoreTopupsDevNet,
        )
        preapprovalCid
      }

      eventually() {
        val preapproval =
          bobValidatorBackend.lookupTransferPreapprovalByParty(onboarding.party).value
        preapproval.payload.lastRenewedAt should not be preapproval.payload.validFrom
        preapproval.contractId should not be initialCid
      }
      val renewTxLog: CheckTxHistoryFn = { case logEntry: TransferTxLogEntry =>
        logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.TransferPreapprovalRenewal.toProto
        logEntry.sender.value.party shouldBe bobValidatorBackend
          .getValidatorPartyId()
          .toProtoPrimitive
        logEntry.sender.value.amount should beWithin(
          -preapprovalFee - smallAmount,
          -preapprovalFee,
        )
        logEntry.receivers shouldBe empty
        logEntry.senderHoldingFees should beWithin(0, smallAmount)
        logEntry.amuletPrice shouldBe amuletPrice
      }
      val expectedTxLogEntries = Seq(renewTxLog, creationTxLog, tapTxLog)
      checkTxHistory(
        bobValidatorWalletClient,
        expectedTxLogEntries,
        trafficTopups = IgnoreTopupsDevNet,
      )

      clue("Check UpdateHistory works for external parties") {
        inside(
          bobValidatorBackend.appState.walletManager
            .valueOrFail("WalletManager is expected to be defined")
            .externalPartyWalletManager
            .lookupExternalPartyWallet(onboarding.party)
            .valueOrFail(s"Expected ${onboarding.party} to have an external party wallet")
            .updateHistory
            .getAllUpdates(None, PageLimit.Max)
            .futureValue
        ) { history =>
          history.size should be >= expectedTxLogEntries.size
        }
      }
    }

    "handle failed automation (direct transfer)" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val validatorTxLogBefore =
        aliceValidatorWalletClient.listTransactions(None, Limit.DefaultMaxPageSize)

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
        Seq({ case logEntry: NotificationTxLogEntry =>
          logEntry.subtype.value shouldBe walletLogEntry.NotificationTransactionSubtype.DirectTransferFailed.toProto
          logEntry.details should startWith("ITR_InsufficientFunds")
        }),
      )

      // Only Alice should see notification (note that aliceValidator is shared between tests)
      val validatorTxLogAfter = aliceValidatorWalletClient.listTransactions(None, Limit.DefaultMaxPageSize)

      withoutDevNetTopups(validatorTxLogBefore) should be(
        withoutDevNetTopups(validatorTxLogAfter)
      )
      checkTxHistory(bobWalletClient, Seq.empty)
    }

    // See #11168
    "two batched failures shouldn't blow up the store ingestion" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      def aliceAcceptedTransferOfferTrigger =
        aliceValidatorBackend
          .userWalletAutomation(aliceWalletClient.config.ledgerApiUser)
          .futureValue
          .trigger[AcceptedTransferOfferTrigger]

      // We disable the trigger and use a large number of transfers to ensure at least some will get batched
      val offerCids = setTriggersWithin(
        triggersToPauseAtStart = Seq(aliceAcceptedTransferOfferTrigger),
        Seq.empty,
      ) {

        val (offerCids, _) =
          actAndCheck(
            "Alice creates transfer offer",
            (1 to 20).map(n =>
              aliceWalletClient.createTransferOffer(
                bobUserParty,
                100.0,
                "direct transfer test",
                CantonTimestamp.now().plus(Duration.ofHours(1)),
                n.toString,
              )
            ),
          )(
            "Bob sees transfer offer",
            offerCids =>
              bobWalletClient.listTransferOffers() should have length offerCids.size.toLong,
          )

        clue("Bob accepts transfer offer") {
          offerCids.foreach(bobWalletClient.acceptTransferOffer)
          // At this point, Alice's automation fails to complete the accepted offers
        }

        offerCids
      }

      eventually() {
        val txs = aliceWalletClient.listTransactions(None, pageSize = offerCids.size)
        // mapping to make it readable
        txs.map(_.eventId) should have size offerCids.size.toLong
        forAll(txs) {
          case logEntry: NotificationTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.NotificationTransactionSubtype.DirectTransferFailed.toProto
            logEntry.details should startWith("ITR_InsufficientFunds")
          case bad =>
            fail(s"Unexpected log entry: $bad")
        }
      }
    }

    "handle failed automation (app payment)" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      val ((reqCid, _), _) = actAndCheck(
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
          paymentAmount(100.0, walletCodegen.Unit.AMULETUNIT),
          paymentInterval = Duration.ofMinutes(60),
          paymentDuration = Duration.ofMinutes(60),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      clue("Alice tries to accept the request and fails") {
        assertCommandFailsDueToInsufficientFunds(
          aliceWalletClient.acceptSubscriptionRequest(request.contractId)
        )
      }

      // Accepting a subscription fails synchronously and should not include a notification
      checkTxHistory(aliceWalletClient, Seq.empty)
    }

    "handle failed automation (subscription payment)" in { implicit env =>
      val aliceUserId = aliceWalletClient.config.ledgerApiUser
      val charlieUserId = charlieWalletClient.config.ledgerApiUser
      val validatorTxLogBefore =
        aliceValidatorWalletClient.listTransactions(None, Limit.DefaultMaxPageSize)

      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      val subscriptionPrice = BigDecimal(75.0)

      clue("Alice taps just enough amulets for one payment") {
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
          paymentAmount(subscriptionPrice, walletCodegen.Unit.AMULETUNIT),
          paymentInterval = Duration.ofMillis(10),
          paymentDuration = Duration.ofMillis(10),
        ),
      )(
        "Request appears in Alices' wallet",
        _ => aliceWalletClient.listSubscriptionRequests().headOption.value,
      )

      val (_, initialPayment) = actAndCheck(
        "Alice accepts the request",
        aliceWalletClient.acceptSubscriptionRequest(request.contractId),
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
      // TODO(DACH-NY/canton-network-node#2034): simplify this test once automation stops retrying forever
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
              subscriptionResult.subscriptionState,
            ),
          )(
            "Alice doesn't see any subscription",
            _ => aliceWalletClient.listSubscriptions() shouldBe empty,
          )

          eventually() {
            inside(aliceWalletClient.listTransactions(None, 100)) {
              case notifications :+ subscriptionPaymentCollected :+ subscriptionPaymentAccepted :+ balanceChange =>
                forExactly(1, notifications) {
                  case logEntry: NotificationTxLogEntry =>
                    logEntry.subtype.value shouldBe walletLogEntry.NotificationTransactionSubtype.SubscriptionExpired.toProto
                    logEntry.details should startWith("Expired")
                  case e => fail(s"Unexpected log entry $e")
                }
                // guaranteed to have at least 1
                forExactly(notifications.size - 1, notifications) {
                  case logEntry: NotificationTxLogEntry =>
                    logEntry.subtype.value shouldBe walletLogEntry.NotificationTransactionSubtype.SubscriptionPaymentFailed.toProto
                    logEntry.details should startWith("ITR_InsufficientFunds")
                  case e => fail(s"Unexpected log entry $e")
                }

                inside(subscriptionPaymentCollected) { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentCollected.toProto
                }
                inside(subscriptionPaymentAccepted) { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentAccepted.toProto
                }
                inside(balanceChange) { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                }
            }
          }

          // Validator should not see any notification (note that aliceValidator is shared between tests)
          val validatorTxLogAfter =
            aliceValidatorWalletClient.listTransactions(None, Limit.DefaultMaxPageSize)
          withoutDevNetTopups(validatorTxLogBefore) should be(
            withoutDevNetTopups(validatorTxLogAfter)
          )

          // Charlie (the provider of the subscription) should see a notification
          checkTxHistory(
            charlieWalletClient,
            Seq(
              { case logEntry: NotificationTxLogEntry =>
                logEntry.subtype.value shouldBe walletLogEntry.NotificationTransactionSubtype.SubscriptionExpired.toProto
              },
              { case logEntry: TransferTxLogEntry =>
                logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.SubscriptionInitialPaymentCollected.toProto
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
      val previousEventId = withoutDevNetTopups(
        sv1WalletClient
          .listTransactions(None, Limit.DefaultMaxPageSize)
      ).headOption
        .map(_.eventId)

      val amuletAmount = BigDecimal(42)
      val amulet = loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          createAmulet(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserId,
            sv1UserParty,
            amount = amuletAmount,
          )
        },
        logs =>
          inside(logs) {
            case logLines if logLines.nonEmpty =>
              logLines
                .filter(_.errorMessage contains ("RuntimeException"))
                .foreach(_.errorMessage should include("Unexpected amulet create event"))
              logLines should have size (env.scans.local.size.toLong + 1) // + 1 for UserWalletTxLog
          },
      )

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          archiveAmulet(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserId,
            sv1UserParty,
            amulet,
          )
        },
        logs =>
          inside(logs) {
            case logLines if logLines.nonEmpty =>
              logLines
                .filter(_.errorMessage contains ("RuntimeException"))
                .foreach(_.errorMessage should include("Unexpected amulet archive event"))
              logLines should have size (env.scans.local.size.toLong + 1) // + 1 for UserWalletTxLog
          },
      )

      // The two above operations (bare create and bare archive of a amulet) will not happen in practice.
      // The user wallet tx log parser should throw an exception when encountering these unexpected events,
      // which should:
      // - Log errors while ingesting the transactions above
      // - Generate "Unknown" log entries in the TxLog
      // - Log errors while reading the TxLog below (recovering the full entry from the index record also involves parsing)
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
        checkTxHistory(
          sv1WalletClient,
          Seq(
            { case _: UnknownTxLogEntry => succeed },
            { case _: UnknownTxLogEntry => succeed },
          ),
          previousEventId = previousEventId,
          trafficTopups = IgnoreTopupsDevNet,
          ignore = {
            case balanceChange: BalanceChangeTxLogEntry =>
              balanceChange.subtype.value == walletLogEntry.TransferTransactionSubtype.WalletAutomation.toProto
            case _ => false
          },
        ),
        entries => forAll(entries)(_.errorMessage should include("Failed to parse transaction")),
      )
    }

    "not blow up with failed CO_TransferPreapprovalSend" in { implicit env =>
      // Note: using Alice and Charlie because manually creating subscriptions requires both
      // the sender and the receiver to be hosted on the same participant.
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      aliceValidatorWalletClient.tap(100) // funds to create preapproval
      createTransferPreapprovalEnsuringItExists(charlieWalletClient, aliceValidatorBackend)

      assertCommandFailsDueToInsufficientFunds(
        aliceWalletClient.transferPreapprovalSend(
          charlieUserParty,
          BigDecimal(10000000),
          UUID.randomUUID().toString,
          Some("this should not go through"),
        )
      )

      aliceWalletClient.listTransactions(None, Limit.DefaultMaxPageSize) shouldBe empty
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
            "the amulet operation failed with a Daml exception: COO_Error(ITR_InsufficientFunds"
          )
        )
      },
    )
  }
}
