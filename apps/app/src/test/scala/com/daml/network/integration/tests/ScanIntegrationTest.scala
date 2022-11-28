package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.round.SummarizingMiningRound
import com.daml.network.codegen.java.da
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
    val (aliceP, bobP) = setupAliceAndBobAndChannel(this)
    aliceRemoteWallet.tap(50)
    aliceRemoteWallet.executeDirectTransfer(bobP, 10)
    eventually(5.seconds) {
      val history = scan.getTxHistory()
      history should have length 2
      val tapCreateTx = history(0)
      val mintEvent = tapCreateTx.events(0)
      inside(mintEvent.parentO) { case Some(mint: Mint) =>
        BigDecimal(mint.node.argument.value.quantity) shouldBe 50
        mint.node.argument.value.receiver shouldBe aliceP.toPrim
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
            argument.value.transfer.sender shouldBe aliceP.toPrim
            // one transfer result for alice, one for bob
            inside(result.value) { case result: da.types.either.Right[_, _] =>
              result.bValue.createdCoins should have length 2
            }
          }

          aliceNew should matchPattern { case CoinCreate(_) => }
          aliceOld should matchPattern { case CoinArchive(_) => }

          inside(bob) { case CoinCreate(coin: CoinContract) =>
            // -0.05 as sender needs to pay half of the transfer fee (0.1)
            BigDecimal(coin.contract.payload.quantity.initialQuantity) shouldBe 9.95
          }
      }
    }
  }

  "get details of a single Coin transfer" in { implicit env =>
    val (aliceP @ _, bobP) = setupAliceAndBobAndChannel(this)
    aliceRemoteWallet.tap(50)
    aliceRemoteWallet.executeDirectTransfer(bobP, 10)

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

  "report correct reference data" in { implicit env =>
    setupAliceAndBobAndChannel(this)
    eventually(1.seconds) {
      scan.getTransferContext().latestOpenMiningRound.map(_.payload.round.number) shouldBe Some(0)
    }

    svc.startSummarizingRound(0)
    eventually() {
      // automation archives the summarizing round and creates the issuing round
      svc.remoteParticipant.ledger_api.acs
        .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
    }
    svc.closeRound(0)
    svc.openRound(1, 1.0)

    eventually(1.seconds) {
      scan.getTransferContext().latestOpenMiningRound.map(_.payload.round.number) shouldBe Some(1)
    }
  }

  "list closed rounds" in { implicit env =>
    val (aliceUserParty @ _, bobUserParty) = setupAliceAndBobAndChannel(this)
    eventually(1.seconds) {
      scan.getTransferContext().latestOpenMiningRound.map(_.payload.round.number) shouldBe Some(0)
    }

    aliceRemoteWallet.tap(200)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 39)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 19)

    svc.openRound(1, 1)

    svc.startSummarizingRound(0)
    eventually() {
      // automation archives the summarizing round and creates the issuing round
      svc.remoteParticipant.ledger_api.acs
        .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
    }
    svc.closeRound(0)

    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 29)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 9)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 1)

    svc.startSummarizingRound(1)
    eventually() {
      // automation archives the summarizing round and creates the issuing round
      svc.remoteParticipant.ledger_api.acs
        .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
    }
    svc.closeRound(1)

    eventually() {
      scan.getClosedRounds() shouldBe empty
    }
  // TODO(#1705): Re-enable ~equivalent to the below once we add an audit log store for closed rounds.
//    val closed = scan.getClosedRounds()
//    inside(closed) { case Seq(round1, round0) =>
//      // TODO(M1-92): make this more robust or don't care about exact values at all
//      round0.payload should be(
//        new ClosedMiningRound(
//          svcParty.toProtoPrimitive,
//          new Round(0),
//          BigDecimal(0.58).bigDecimal.setScale(10),
//          BigDecimal(0.2).bigDecimal.setScale(10),
//          BigDecimal(0.0).bigDecimal.setScale(10),
//          BigDecimal(360.705).bigDecimal.setScale(10),
//          BigDecimal(57.71).bigDecimal.setScale(10),
//          BigDecimal(302.215).bigDecimal.setScale(10),
//          round0.payload.observers,
//        )
//      )
//      round1.payload should be(
//        new ClosedMiningRound(
//          svcParty.toProtoPrimitive,
//          new Round(1),
//          BigDecimal(0.39).bigDecimal.setScale(10),
//          BigDecimal(0.3).bigDecimal.setScale(10),
//          BigDecimal(0.0000048225).bigDecimal.setScale(10),
//          BigDecimal(356.8949855325).bigDecimal.setScale(10),
//          BigDecimal(29 + 9 + 1 - 0.195).bigDecimal.setScale(10),
//          BigDecimal(317.3999855325).bigDecimal.setScale(10),
//          round1.payload.observers,
//        )
//      )

  // TODO(#832): do the math and verify that the values above are correct
//    }
  }

}
