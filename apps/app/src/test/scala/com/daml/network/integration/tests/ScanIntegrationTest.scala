package com.daml.network.integration.tests

import com.daml.network.history.*
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.{CoinTestUtil, ExerciseNode}

import scala.concurrent.duration.*

// TODO(M1-92): Add tests that cover all possible CoinEvents
class ScanIntegrationTest extends CoinIntegrationTest with CoinTestUtil {

  "restart cleanly" in { implicit env =>
    scan.stop()
    scan.startSync()
  }

  "see Coin transfers" in { implicit env =>
    val (alice, bob) = onboardAliceAndBob(this)
    aliceWallet.tap(50.0)
    p2pTransfer(aliceWallet, bobWallet, bob, 42.0)
    eventually(5.seconds) {
      val history = scan.getTxHistory()
      history should have length 2
      val tapCreateTx = history(0)
      val mintEvent = tapCreateTx.events(0)
      inside(mintEvent.parentO) { case Some(mint: Mint) =>
        BigDecimal(mint.node.argument.value.quantity) shouldBe 50
        mint.node.argument.value.receiver shouldBe alice.toPrim
      }

      val transferTx = history(1)
      val transferEvents = transferTx.events
      // Coins in order: archive 50, bob-10, alice 40-ish,
      inside(transferEvents) {
        case Seq(
              // alice's input coin
              CoinEvent(aliceOld, transferParentNode),
              // bob's new coin
              CoinEvent(bob, transferParentNode2),
              // alice's change after deducting the quantity send to bob
              CoinEvent(aliceNew, transferParentNode3),
            ) =>
          // all three coin-events created by the transfer should have the transfer node as parent
          transferParentNode should matchPattern { case Some(_: Transfer) => }
          transferParentNode shouldBe transferParentNode2
          transferParentNode2 shouldBe transferParentNode3

          inside(transferParentNode) { case Some(Transfer(ExerciseNode(argument, result))) =>
            argument.value.transfer.sender shouldBe alice.toPrim
            // one transfer result for alice, one for bob
            result.value.createdCoins should have length 2
          }

          aliceNew should matchPattern { case CoinCreate(_) => }
          aliceOld should matchPattern { case CoinArchive(_) => }

          inside(bob) { case CoinCreate(coin: CoinContract) =>
            // -0.05 as sender needs to pay half of the transfer fee (0.1)
            BigDecimal(coin.contract.payload.quantity.initialQuantity) shouldBe 42.0
          }
      }
    }
  }

  "get details of a single Coin transfer" in { implicit env =>
    val (_, bob) = onboardAliceAndBob(this)
    aliceWallet.tap(50.0)
    p2pTransfer(aliceWallet, bobWallet, bob, 42.0)

    eventually(5.seconds) {
      val history = scan.getTxHistory()
      history should have length 2
      val tapTransaction = history(0)
      val tap = scan.getCoinTransactionTreePretty(tapTransaction.txMetadata.transactionId)

      tap.transactionId shouldBe tapTransaction.txMetadata.transactionId
      tap.forestOfEventsASCII should (include("alice_wallet_user") and include(
        "Tap"
      ) and not include ("bob_wallet_user"))

      val transferTransaction = history(1)
      val transfer =
        scan.getCoinTransactionTreePretty(transferTransaction.txMetadata.transactionId)

      transfer.transactionId shouldBe transferTransaction.txMetadata.transactionId
      transfer.forestOfEventsASCII should (include("alice_wallet_user") and include(
        "CoinRules_Transfer"
      ) and include("bob_wallet_user"))
    }

  }
}
