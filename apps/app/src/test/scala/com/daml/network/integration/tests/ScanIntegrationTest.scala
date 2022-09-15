package com.daml.network.integration.tests

import com.daml.network.codegen.CC.CoinRules.CoinRules
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.history._
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CommonCoinAppInstanceReferences, ExerciseNode}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration._

// TODO(M1-92): Add tests that cover all possible CoinEvents
class ScanIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withSetup(env => {
        import env._
        participants.all.foreach(_.domains.connect_local(da))
      })

  "see Coin transfers" in { implicit env =>
    val (aliceP, bobP) = setup(env)
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
          transferParentNode should matchPattern { case Some(transfer: Transfer) => }
          transferParentNode shouldBe transferParentNode2
          transferParentNode2 shouldBe transferParentNode3

          inside(transferParentNode) { case Some(Transfer(ExerciseNode(argument, result))) =>
            argument.transfer.sender shouldBe aliceP.toPrim
            // one transfer result for alice, one for bob
            result.createdCoins should have length 2
          }

          aliceNew should matchPattern { case CoinCreate(_) => }
          aliceOld should matchPattern { case CoinArchive(_) => }

          inside(bob) { case CoinCreate(coin: CoinContract) =>
            coin.contract.payload.quantity.initialQuantity shouldBe BigDecimal(10)
          }
      }
    }
  }

  "get details of a single Coin transfer" in { implicit env =>
    val (aliceP, bobP) = setup(env)
    val tappedCoinCid = aliceRemoteWallet.tap(50)
    aliceRemoteWallet.executeDirectTransfer(bobP, 10, tappedCoinCid)

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
    setup(env)
    eventually(1.seconds) { scan.getReferenceData().currentRound shouldBe 0 }

    svc.startClosingRound(0)
    svc.startIssuingRound(0)
    svc.closeRound(0)
    svc.openRound(1.0)

    eventually(1.seconds) { scan.getReferenceData().currentRound shouldBe 1 }
  }

  def setup(implicit env: CoinTestConsoleEnvironment): (PartyId, PartyId) = {
    import env._
    // Onboard alice on her self-hosted validator
    val aliceValidatorParty = aliceValidator.initialize()
    val aliceDamlUser = aliceRemoteWallet.config.damlUser
    aliceWallet.initialize(aliceValidatorParty)
    val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

    // Onboard bob on his self-hosted validator
    val bobValidatorParty = bobValidator.initialize()
    val bobDamlUser = bobRemoteWallet.config.damlUser
    bobWallet.initialize(bobValidatorParty)
    val bobUserParty = bobValidator.onboardUser(bobDamlUser)

    // ensure the participants see the CoinRules
    aliceWallet.remoteParticipant.ledger_api.acs.await(aliceValidatorParty, CoinRules)
    bobWallet.remoteParticipant.ledger_api.acs.await(bobValidatorParty, CoinRules)

    val proposalId = aliceRemoteWallet.proposePaymentChannel(bobUserParty)
    // Bob monitors proposals and accepts the one
    utils.retry_until_true(bobRemoteWallet.listPaymentChannelProposals().size == 1)
    bobRemoteWallet.acceptPaymentChannelProposal(proposalId)

    utils.retry_until_true(aliceRemoteWallet.listPaymentChannels().size == 1)
    (aliceUserParty, bobUserParty)
  }
}
