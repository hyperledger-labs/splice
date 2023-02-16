package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil, SplitwellTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SplitwellTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwell" should {

    "support provider-hosted mode" in { implicit env =>
      val (aliceUserParty, bobUserParty, charlieUserParty, _, key) =
        initSplitwellTest()

      aliceSplitwell.enterPayment(
        key,
        4200.0,
        "payment",
      )
      bobWallet.tap(4000)
      splitwellTransfer(bobSplitwell, bobWallet, aliceUserParty, BigDecimal(1000.0), key)

      eventually() {
        bobSplitwell.listBalanceUpdates(key) should have size 2
      }
      bobSplitwell.listBalances(key) shouldBe Seq(aliceUserParty -> -1100).toMap

      aliceSplitwell.listBalanceUpdates(key) should have size 2
      aliceSplitwell.listBalances(key) shouldBe Seq(bobUserParty -> 1100).toMap

      inside(charlieSplitwell.listGroupInvites()) { case Seq(invite) =>
        charlieSplitwell.acceptInvite(invite.contractId)
      }
      eventually() {
        aliceSplitwell.listAcceptedGroupInvites("group1") should have size 1
      }
      inside(aliceSplitwell.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwell.joinGroup(accepted.contractId)
      }

      splitwellValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .awaitJava(splitwellCodegen.Group.COMPANION)(providerSplitwellBackend.getProviderPartyId())

      charlieSplitwell.listBalances(key) shouldBe Map.empty
      charlieSplitwell.enterPayment(key, 3300.0, "payment")
      eventually() {
        charlieSplitwell.listBalances(key) shouldBe Map(
          aliceUserParty -> 1100,
          bobUserParty -> 1100,
        )
      }

      eventually()(aliceSplitwell.listBalanceUpdates(key) should have size 3)
      aliceSplitwell.listBalances(key) shouldBe Map(
        bobUserParty -> 1100,
        charlieUserParty -> -1100,
      )

      aliceSplitwell.net(
        key,
        Map(
          aliceUserParty -> Map(bobUserParty -> -1100, charlieUserParty -> 1100),
          bobUserParty -> Map(aliceUserParty -> 1100, charlieUserParty -> -1100),
          charlieUserParty -> Map(aliceUserParty -> -1100, bobUserParty -> 1100),
        ),
      )
      eventually() {
        aliceSplitwell.listBalances(key) shouldBe Map(bobUserParty -> 0, charlieUserParty -> 0)
        bobSplitwell.listBalances(key) shouldBe Map(aliceUserParty -> 0, charlieUserParty -> -2200)
        charlieSplitwell.listBalances(key) shouldBe Map(aliceUserParty -> 0, bobUserParty -> 2200)
        splitwellProviderWallet.listAppRewardCoupons() should have length 1
      }
    }
  }
}
