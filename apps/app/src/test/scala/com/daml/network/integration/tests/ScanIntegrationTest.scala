package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.history._
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.CoinRules.CoinRules

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

  "see CC transfers" in { implicit env =>
    val (aliceP, bobP) = setup(env)
    val tappedCoinCid = aliceWallet.tap(50)
    aliceWallet.executeDirectTransfer(bobP, 10, tappedCoinCid)
    import scala.concurrent.duration._
    eventually(5.seconds) {
      val history = scan.getTxHistory()
      history should have length 2
      val tapCreateTx = history(0)
      val tapEvent = tapCreateTx.events(0)
      inside(tapEvent.parentO) { case Some(tap: Tap) =>
        tap.argument.quantity shouldBe 50
        tap.argument.receiver shouldBe aliceP.toPrim
      }

      val transferTx = history(1)
      val transferEvents = transferTx.events
      // Coins in order: archive 50, bob-10, alice 40-ish,
      inside(transferEvents) {
        case Seq(
              // alice's new coin after deducting the quantity send to bob
              CoinEvent(aliceOld, transferParentNode),
              // bob's new coin
              CoinEvent(bob, transferParentNode2),
              // alice's input coin
              CoinEvent(aliceNew, transferParentNode3),
            ) =>
          // all three coin-events created by the transfer should have the transfer node as parent
          transferParentNode should matchPattern { case Some(transfer) => }
          transferParentNode shouldBe transferParentNode2
          transferParentNode2 shouldBe transferParentNode3

          inside(transferParentNode) { case Some(Transfer(argument, results)) =>
            argument.transfer.sender shouldBe aliceP.toPrim
            // one transfer result for alice, one for bob
            results should have length 2
          }

          aliceNew should matchPattern { case CoinCreate(_) => }
          aliceOld should matchPattern { case CoinArchive(_) => }

          inside(bob) { case CoinCreate(coin) =>
            coin.payload.quantity.initialQuantity shouldBe BigDecimal(10)
          }
      }
    }
  }

  def setup(implicit env: CoinTestConsoleEnvironment): (PartyId, PartyId) = {
    import env._
    // Onboard alice on her self-hosted validator
    val aliceValidatorParty = aliceValidator.initialize()
    val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
    aliceWallet.initialize(aliceValidatorParty)

    // Onboard bob on his self-hosted validator
    val bobValidatorParty = bobValidator.initialize()
    val bobUserParty = bobValidator.onboardUser(bobWallet.config.damlUser)
    bobWallet.initialize(bobValidatorParty)
    // ensure the participants see the CoinRules
    aliceWallet.remoteParticipant.ledger_api.acs.await(aliceValidatorParty, CoinRules)
    bobWallet.remoteParticipant.ledger_api.acs.await(bobValidatorParty, CoinRules)

    val proposalId = aliceWallet.proposePaymentChannel(bobUserParty)
    // Bob monitors proposals and accepts the one
    utils.retry_until_true(bobWallet.listPaymentChannelProposals().size == 1)
    bobWallet.acceptPaymentChannelProposal(proposalId)

    utils.retry_until_true(aliceWallet.listPaymentChannels().size == 1)
    (aliceUserParty, bobUserParty)
  }
}
