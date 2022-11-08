package com.daml.network.integration.tests

import com.daml.network.codegen.CC.Round.{ClosedMiningRound, Round}
import com.daml.network.codegen.DA
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.history._
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CommonCoinAppInstanceReferences, ExerciseNode, PaymentChannelTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.duration._

// TODO(M1-92): Add tests that cover all possible CoinEvents
class ScanIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences
    with PaymentChannelTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  "restart cleanly" in { implicit env =>
    scan.stop()
    scan.startSync()
  }

  "see Coin transfers" in { implicit env =>
    val (aliceP, bobP) = setupAliceAndBobAndChannel(env)
    aliceRemoteWallet.tap(50)
    aliceRemoteWallet.executeDirectTransfer(bobP, 10)
    eventually(5.seconds) {
      val history = scan.getTxHistory()
      history should have length 2
      val tapCreateTx = history(0)
      val tapEvent = tapCreateTx.events(0)
      inside(tapEvent.parentO) { case Some(tap: Tap) =>
        tap.node.argument.quantity shouldBe 50
        tap.node.argument.receiver shouldBe aliceP.toPrim
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
            argument.transfer.sender shouldBe aliceP.toPrim
            // one transfer result for alice, one for bob
            inside(result) { case DA.Types.Either.Right(result) =>
              result.createdCoins should have length 2
            }
          }

          aliceNew should matchPattern { case CoinCreate(_) => }
          aliceOld should matchPattern { case CoinArchive(_) => }

          inside(bob) { case CoinCreate(coin: CoinContract) =>
            // -0.05 as sender needs to pay half of the transfer fee (0.1)
            coin.contract.payload.quantity.initialQuantity shouldBe BigDecimal(9.95)
          }
      }
    }
  }

  "get details of a single Coin transfer" in { implicit env =>
    val (aliceP @ _, bobP) = setupAliceAndBobAndChannel(env)
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
    setupAliceAndBobAndChannel(env)
    eventually(1.seconds) {
      scan.getTransferContext().latestOpenMiningRound.map(_.payload.round.number) shouldBe Some(0)
    }

    svc.startClosingRound(0)
    svc.startIssuingRound(0)
    svc.closeRound(0)
    svc.openRound(1, 1.0)

    eventually(1.seconds) {
      scan.getTransferContext().latestOpenMiningRound.map(_.payload.round.number) shouldBe Some(1)
    }
  }

  "list closed rounds" in { implicit env =>
    val (aliceUserParty @ _, bobUserParty) = setupAliceAndBobAndChannel(env)
    eventually(1.seconds) {
      scan.getTransferContext().latestOpenMiningRound.map(_.payload.round.number) shouldBe Some(0)
    }

    aliceRemoteWallet.tap(200)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 39)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 19)

    svc.openRound(1, 1)

    svc.startClosingRound(0)
    svc.startIssuingRound(0)
    svc.closeRound(0)

    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 29)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 9)
    aliceRemoteWallet.executeDirectTransfer(bobUserParty, 1)

    svc.startClosingRound(1)
    svc.startIssuingRound(1)
    svc.closeRound(1)

    val closed = scan.getClosedRounds()
    inside(closed) { case Seq(round1, round0) =>
      // TODO(M1-92): make this more robust or don't care about exact values at all
      round0.payload should be(
        ClosedMiningRound(
          svc = svcParty.toPrim,
          round = Round(number = 0),
          totalTransferFees = BigDecimal(0.58),
          totalAdminFees = BigDecimal(0.2),
          totalHoldingFees = BigDecimal(0.0),
          totalTransferInputs = BigDecimal(360.705),
          totalNonSelfTransferOutputs = BigDecimal(57.71),
          totalSelfTransferOutputs = BigDecimal(302.215),
          observers = round0.payload.observers,
        )
      )
      round1.payload should be(
        ClosedMiningRound(
          svc = svcParty.toPrim,
          round = Round(number = 1),
          totalTransferFees = BigDecimal(0.39),
          totalAdminFees = BigDecimal(0.3),
          totalHoldingFees = BigDecimal(0.0000048225),
          totalTransferInputs = BigDecimal(356.8949855325),
          totalNonSelfTransferOutputs = BigDecimal(29 + 9 + 1 - 0.195),
          totalSelfTransferOutputs = BigDecimal(317.3999855325),
          observers = round1.payload.observers,
        )
      )

    // TODO(i832): do the math and verify that the values above are correct
    }
  }

}
