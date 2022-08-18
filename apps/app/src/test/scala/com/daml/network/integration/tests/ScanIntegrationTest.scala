package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.history.{CoinArchive, CoinCreate, CoinEvent, Tap}
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
    val coinCid = aliceWallet.tap(50)
    aliceWallet.executeDirectTransfer(bobP, 10, coinCid)
    import scala.concurrent.duration._
    eventually(5.seconds) {
      val history = scan.getTxHistory()
      history should have length 2
      val tapCreateTx = history(0)
      val tapEvent = tapCreateTx.events(0)
      inside(tapEvent.ancestorO) { case Some(tap: Tap) =>
        tap.value.quantity shouldBe 50
        tap.value.receiver shouldBe aliceP.toPrim
      }

      val transferTx = history(1)
      val transferEvents = transferTx.events
      // Coins in order: alice 40-ish, bob-10, archive 50
      inside(transferEvents) {
        case Seq(
              CoinEvent(aliceNew, transferAncestor),
              CoinEvent(bob, transferAncestor2),
              CoinEvent(aliceOld, transferAncestor3),
            ) =>
          transferAncestor should matchPattern { case Some(transfer) => }
          transferAncestor shouldBe transferAncestor2
          transferAncestor2 shouldBe transferAncestor3

          aliceNew should matchPattern { case CoinCreate(_) => }
          aliceOld should matchPattern { case CoinArchive(_) => }

          inside(bob) { case CoinCreate(coin) =>
            // TODO(Arne): without int conversion, I see: `10.0000000000 was not equal to 10` (and same when I compare against `BigDecimal(10)`)
            //  This seems suspicious
            coin.payload.quantity.initialQuantity.intValue shouldBe 10
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
