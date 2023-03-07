package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.WalletAppClientReference
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTestWithSharedEnvironment
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.scalatest.Assertion
import org.slf4j.event.Level

class WalletTxLogTimeBasedIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
  // We disable the automation for this suite.
  override def environmentDefinition: CoinEnvironmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
        )(config)
      )
  }

  // Amount paid by `createSelfPaymentRequest()`
  private val selfPaymentAmount = 10.0

  // Upper bound for fees in any of the above transfers
  private val smallAmount = 1.0

  private def checkTxHistory(
      wallet: WalletAppClientReference,
      expected: Seq[PartialFunction[UserWalletTxLogParser.TxLogEntry, Assertion]],
  ): Unit = {

    val actual = wallet.listTransactions(None, pageSize = 100000)

    actual should have length expected.size.toLong

    actual.zip(expected).zipWithIndex.foreach { case ((entry, pf), i) =>
      clue(s"Entry at position $i") {
        inside(entry)(pf)
      }
    }

    clue("Paginated result should be equal to non-paginated result") {
      val paginatedResult = Iterator
        .unfold[Seq[UserWalletTxLogParser.TxLogEntry], Option[String]](None)(beginAfterId => {
          val page = wallet.listTransactions(beginAfterId, pageSize = 2)
          if (page.isEmpty)
            None
          else
            Some(page -> Some(page.last.indexRecord.eventId))
        })
        .toSeq
        .flatten

      paginatedResult should contain theSameElementsInOrderAs actual
    }
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

      clue("Create, accept, and collect self-payment request") {
        val (deliveryOfferCid, reqCid, _) = createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
        )
        actAndCheck("Alice accepts payment request", aliceWallet.acceptAppPaymentRequest(reqCid))(
          "Payment request disappears from list",
          _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
        )
        collectAcceptedAppPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          deliveryOfferCid,
        )
      }

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
            logEntry.sender._1 shouldBe aliceUserParty.toProtoPrimitive
            logEntry.receivers shouldBe empty
            logEntry.sender._2 should be > BigDecimal(selfPaymentAmount)
            logEntry.sender._2 should be < BigDecimal(selfPaymentAmount + smallAmount)
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // First part of collecting the payment: Unlocking the 10CC locked coin.
            // Note: this and the next entry should really be merged in the history.
            logEntry.amount should be > BigDecimal(selfPaymentAmount)
            logEntry.amount should be < BigDecimal(selfPaymentAmount + smallAmount)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.Transfer =>
            // Second part of collecting the payment: Transferring the coin to ourselves.
            // Note: this and the previous entry should really be merged in the history.
            logEntry.sender._1 shouldBe aliceUserParty.toProtoPrimitive
            logEntry.receivers shouldBe empty
            logEntry.sender._2 should be > BigDecimal(-smallAmount)
            logEntry.sender._2 should be < BigDecimal(smallAmount)
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

      clue("Create accept, and reject self-payment request") {
        val (deliveryOfferCid, reqCid, _) = createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
        )
        actAndCheck("Alice accepts payment request", aliceWallet.acceptAppPaymentRequest(reqCid))(
          "Payment request disappears from list",
          _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
        )
        rejectAcceptedAppPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          deliveryOfferCid,
        )
      }

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
            logEntry.sender._1 shouldBe aliceUserParty.toProtoPrimitive
            logEntry.receivers shouldBe empty
            logEntry.sender._2 should be > BigDecimal(selfPaymentAmount)
            logEntry.sender._2 should be < BigDecimal(selfPaymentAmount + smallAmount)
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
          },
          { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
            // Rejecting the accepted self-payment request returned the 10CC locked coin.
            logEntry.amount should be > BigDecimal(selfPaymentAmount)
            logEntry.amount should be < BigDecimal(selfPaymentAmount + smallAmount)
          },
        ),
      )
    }

    "handle expired coins in a transaction history" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      actAndCheck("Tap to get some coins", aliceWallet.tap(0.000005))(
        "Wait for tap to appear in history",
        _ =>
          aliceWallet.listTransactions(None, 5) should matchPattern {
            case Seq(_: UserWalletTxLogParser.TxLogEntry.BalanceChange) =>
          },
      )

      aliceWallet.list().coins should have size 1

      // advance 4 ticks to expire the coin
      // TODO (#2845) Adapt this once we properly handle expired coins.
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        {
          Range(0, 4).foreach(_ => advanceRoundsByOneTick)

          eventually() {
            aliceWallet.list().coins should have size 0
          }
        },
        entries =>
          forAtLeast(1, entries)(
            _.message should include(
              "Coin archive events are not included in the transaction history."
            )
          ),
      )
    }
  }
}
