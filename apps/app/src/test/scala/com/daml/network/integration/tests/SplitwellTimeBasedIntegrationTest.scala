package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.daml.network.console.WalletAppClientReference
import com.daml.network.console.SplitwellAppClientReference

class SplitwellTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

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

    def initSplitwellTest()(implicit
        env: CoinTests.CoinTestConsoleEnvironment
    ) = {
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      // The provider's wallet is auto-onboarded, so we just need to wait for it to be ready
      waitForWalletUser(splitwellProviderWallet)
      val splitwellProviderParty = providerSplitwellBackend.getProviderPartyId()

      // Setup install contracts
      Seq(
        (aliceSplitwell, aliceUserParty),
        (bobSplitwell, bobUserParty),
        (charlieSplitwell, charlieUserParty),
      ).foreach { case (splitwell, party) =>
        splitwell.createInstallRequest()
        splitwell.ledgerApi.ledger_api.acs
          .awaitJava(splitwellCodegen.SplitwellInstall.COMPANION)(party)
      }

      aliceSplitwell.requestGroup("group1")
      eventually() {
        aliceSplitwell.listGroups() should have size 1
      }
      aliceSplitwell.createGroupInvite(
        "group1",
        Seq(bobUserParty, charlieUserParty),
      )

      eventually() {
        bobSplitwell.listGroupInvites() should not be empty
      }
      inside(bobSplitwell.listGroupInvites()) { case Seq(invite) =>
        bobSplitwell.acceptInvite(invite.contractId)
      }

      eventually() {
        aliceSplitwell.listAcceptedGroupInvites("group1") should not be empty
      }
      inside(aliceSplitwell.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwell.joinGroup(accepted.contractId)
      }

      val key = GrpcSplitwellAppClient.GroupKey(
        aliceUserParty,
        aliceSplitwell.getProviderPartyId(),
        "group1",
      )

      actAndCheck(
        "Self-grant a featured app right",
        splitwellProviderWallet.selfGrantFeaturedAppRight(),
      )(
        "Wait for right to be ingested",
        // We are waiting for scan to ingest the featured app right, and not through the provider's wallet,
        // to make sure that this right will be used when collecting payments.
        _ =>
          scan
            .lookupFeaturedAppRight(splitwellProviderParty)
            .getOrElse(
              fail("Scan did not ingest a featured app right contract for splitwell provider")
            ),
      )

      (aliceUserParty, bobUserParty, charlieUserParty, splitwellProviderParty, key)
    }

    def transfer(
        senderSplitwell: SplitwellAppClientReference,
        senderWallet: WalletAppClientReference,
        receiver: PartyId,
        amount: BigDecimal,
        key: GrpcSplitwellAppClient.GroupKey,
    ) = {
      senderSplitwell.initiateTransfer(
        key,
        Seq(
          new walletCodegen.ReceiverCCAmount(
            receiver.toProtoPrimitive,
            amount.bigDecimal,
          )
        ),
      )
      eventually()(senderWallet.listAppPaymentRequests() should not be empty)
      inside(senderWallet.listAppPaymentRequests()) { case Seq(request) =>
        senderWallet.acceptAppPaymentRequest(request.contractId)
      }

    }

    "support provider-hosted mode" in { implicit env =>
      val (aliceUserParty, bobUserParty, charlieUserParty, splitwellProviderParty, key) =
        initSplitwellTest()

      aliceSplitwell.enterPayment(
        key,
        4200.0,
        "payment",
      )
      bobWallet.tap(4000)
      transfer(bobSplitwell, bobWallet, aliceUserParty, BigDecimal(1000.0), key)

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

      splitwellValidator.remoteParticipantWithAdminToken.ledger_api.acs
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
      val couponId = splitwellProviderWallet.listAppRewardCoupons().head.contractId

      // TODO(#2100): cleanup - completely separate the functionality test from the rewards, move all reward testing to WalletTimeBasedIntegrationTest
      actAndCheck(
        "Advance rounds until splitwell gets its rewards",
        Seq(1, 2, 3).foreach(_ => advanceRoundsByOneTick),
      )(
        "Rewards issued to splitwell",
        _ => {
          splitwellProviderWallet
            .listAppRewardCoupons()
            .map(_.contractId) should not contain couponId
          checkWallet(splitwellProviderParty, splitwellProviderWallet, Seq((192.0, 193.0)))
        },
      )

      actAndCheck(
        "Splitwell cancels its own featured app right",
        splitwellProviderWallet.cancelFeaturedAppRight(),
      )(
        "Splitwell is no longer featured",
        _ => scan.lookupFeaturedAppRight(splitwellProviderParty) should be(None),
      )

      actAndCheck(
        "Another transfer from Bob to Alice", {
          bobSplitwell.initiateTransfer(
            key,
            Seq(
              new walletCodegen.ReceiverCCAmount(
                aliceUserParty.toProtoPrimitive,
                BigDecimal(1000.0).bigDecimal,
              )
            ),
          )
          eventually()(bobWallet.listAppPaymentRequests() should not be empty)
          inside(bobWallet.listAppPaymentRequests()) { case Seq(request) =>
            bobWallet.acceptAppPaymentRequest(request.contractId)
          }
        },
      )(
        "Await another coupon",
        _ => splitwellProviderWallet.listAppRewardCoupons() should have length 1,
      )

      actAndCheck("Advance three more rounds", Seq(1, 2, 3).foreach(_ => advanceRoundsByOneTick))(
        "Receive awards, but significantly smaller",
        _ => checkWallet(splitwellProviderParty, splitwellProviderWallet, Seq((194.0, 194.5))),
      )
    }
  }
}
