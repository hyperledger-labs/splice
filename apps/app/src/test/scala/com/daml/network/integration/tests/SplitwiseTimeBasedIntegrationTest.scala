package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.splitwise.admin.api.client.commands.GrpcSplitwiseAppClient
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SplitwiseTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  private val darPath = "daml/splitwise/.daml/dist/splitwise-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwise" should {
    "support provider-hosted mode" in { implicit env =>
      // Onboard users
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      val splitwiseProviderParty = onboardWalletUser(splitwiseProviderWallet, splitwiseValidator)

      actAndCheck(
        "Self-grant a featured app right",
        splitwiseProviderWallet.selfGrantFeaturedAppRight(),
      )(
        "Wait for right to be ingested",
        // We are waiting for scan to ingest the featured app right, and not through the provider's wallet,
        // to make sure that this right will be used when collecting payments.
        _ =>
          scan
            .lookupFeaturedAppRight(splitwiseProviderParty)
            .getOrElse(
              fail("Scan did not ingest a featured app right contract for splitwise provider")
            ),
      )

      // Setup install contracts
      Seq(
        (aliceSplitwise, aliceUserParty),
        (bobSplitwise, bobUserParty),
        (charlieSplitwise, charlieUserParty),
      ).foreach { case (splitwise, party) =>
        splitwise.createInstallRequest()
        splitwise.ledgerApi.ledger_api.acs
          .awaitJava(splitwiseCodegen.SplitwiseInstall.COMPANION)(party)
      }

      aliceSplitwise.requestGroup("group1")
      eventually() {
        aliceSplitwise.listGroups() should have size 1
      }
      aliceSplitwise.createGroupInvite(
        "group1",
        Seq(bobUserParty, charlieUserParty),
      )

      eventually() {
        bobSplitwise.listGroupInvites() should not be empty
      }
      inside(bobSplitwise.listGroupInvites()) { case Seq(invite) =>
        bobSplitwise.acceptInvite(invite.contractId)
      }

      eventually() {
        aliceSplitwise.listAcceptedGroupInvites("group1") should not be empty
      }
      inside(aliceSplitwise.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwise.joinGroup(accepted.contractId)
      }

      val key = GrpcSplitwiseAppClient.GroupKey(
        aliceUserParty,
        aliceSplitwise.getProviderPartyId(),
        "group1",
      )

      aliceSplitwise.enterPayment(
        key,
        4200.0,
        "payment",
      )
      bobSplitwise.initiateTransfer(
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
        bobWallet.tap(4000)
        bobWallet.acceptAppPaymentRequest(request.contractId)
      }
      eventually() {
        bobSplitwise.listBalanceUpdates(key) should have size 2
      }
      bobSplitwise.listBalances(key) shouldBe Seq(aliceUserParty -> -1100).toMap

      aliceSplitwise.listBalanceUpdates(key) should have size 2
      aliceSplitwise.listBalances(key) shouldBe Seq(bobUserParty -> 1100).toMap

      inside(charlieSplitwise.listGroupInvites()) { case Seq(invite) =>
        charlieSplitwise.acceptInvite(invite.contractId)
      }
      eventually() {
        aliceSplitwise.listAcceptedGroupInvites("group1") should have size 1
      }
      inside(aliceSplitwise.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwise.joinGroup(accepted.contractId)
      }

      splitwiseValidator.remoteParticipantWithAdminToken.ledger_api.acs
        .awaitJava(splitwiseCodegen.Group.COMPANION)(providerSplitwiseBackend.getProviderPartyId())

      charlieSplitwise.listBalances(key) shouldBe Map.empty
      charlieSplitwise.enterPayment(key, 3300.0, "payment")
      eventually() {
        charlieSplitwise.listBalances(key) shouldBe Map(
          aliceUserParty -> 1100,
          bobUserParty -> 1100,
        )
      }

      eventually()(aliceSplitwise.listBalanceUpdates(key) should have size 3)
      aliceSplitwise.listBalances(key) shouldBe Map(
        bobUserParty -> 1100,
        charlieUserParty -> -1100,
      )

      aliceSplitwise.net(
        key,
        Map(
          aliceUserParty -> Map(bobUserParty -> -1100, charlieUserParty -> 1100),
          bobUserParty -> Map(aliceUserParty -> 1100, charlieUserParty -> -1100),
          charlieUserParty -> Map(aliceUserParty -> -1100, bobUserParty -> 1100),
        ),
      )
      eventually() {
        aliceSplitwise.listBalances(key) shouldBe Map(bobUserParty -> 0, charlieUserParty -> 0)
        bobSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, charlieUserParty -> -2200)
        charlieSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, bobUserParty -> 2200)
        splitwiseProviderWallet.listAppRewardCoupons() should have length 1
      }
      val couponId = splitwiseProviderWallet.listAppRewardCoupons().head.contractId

      actAndCheck(
        "Advance rounds until splitwise gets its rewards",
        Seq(1, 2, 3).foreach(_ => advanceRoundsByOneTick),
      )(
        "Rewards issued to splitwise",
        _ => {
          // Redeeming the coupon generates a new reward coupon, so we are not checking that the list of reward coupons is empty here,
          // but instead just that the original one has been archived
          splitwiseProviderWallet
            .listAppRewardCoupons()
            .map(_.contractId) should not contain couponId
          checkWallet(splitwiseProviderParty, splitwiseProviderWallet, Seq((189.0, 190.0)))
        },
      )

      actAndCheck(
        "Splitwise cancels its own featured app right",
        splitwiseProviderWallet.cancelFeaturedAppRight(),
      )(
        "Splitwise is no longer featured",
        _ => scan.lookupFeaturedAppRight(splitwiseProviderParty) should be(None),
      )

      actAndCheck(
        "Another transfer from Bob to Alice", {
          bobSplitwise.initiateTransfer(
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
        _ => splitwiseProviderWallet.listAppRewardCoupons() should have length 2,
      )

      actAndCheck("Advance three more rounds", Seq(1, 2, 3).foreach(_ => advanceRoundsByOneTick))(
        "Receive awards, but significantly smaller",
        _ => checkWallet(splitwiseProviderParty, splitwiseProviderWallet, Seq((191.0, 191.5))),
      )

    }
  }
}
