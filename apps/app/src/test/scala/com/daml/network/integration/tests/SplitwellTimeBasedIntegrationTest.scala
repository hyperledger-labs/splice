package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{SplitwellTestUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SplitwellTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(darPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(darPath)
      })

  "splitwell" should {

    "support provider-hosted mode" in { implicit env =>
      val (aliceUserParty, bobUserParty, charlieUserParty, _, key, invite) =
        initSplitwellTest()

      aliceSplitwellClient.enterPayment(
        key,
        4200.0,
        "payment",
      )
      bobWalletClient.tap(4000)
      splitwellTransfer(
        bobSplitwellClient,
        bobWalletClient,
        aliceUserParty,
        BigDecimal(1000.0),
        key,
      )

      eventually() {
        bobSplitwellClient.listBalanceUpdates(key) should have size 2
      }
      bobSplitwellClient.listBalances(key) shouldBe Seq(aliceUserParty -> -1100).toMap

      aliceSplitwellClient.listBalanceUpdates(key) should have size 2
      aliceSplitwellClient.listBalances(key) shouldBe Seq(bobUserParty -> 1100).toMap

      charlieSplitwellClient.acceptInvite(invite)

      eventually() {
        aliceSplitwellClient.listAcceptedGroupInvites("group1") should have size 1
      }
      inside(aliceSplitwellClient.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwellClient.joinGroup(accepted.contractId)
      }

      eventually() {
        inside(charlieSplitwellClient.listGroups()) { case Seq(singleGroup) =>
          singleGroup.contract.payload.id shouldBe invite.contract.payload.group.id
        }
      }

      charlieSplitwellClient.listBalances(key) shouldBe Map.empty
      charlieSplitwellClient.enterPayment(key, 3300.0, "payment")
      eventually() {
        charlieSplitwellClient.listBalances(key) shouldBe Map(
          aliceUserParty -> 1100,
          bobUserParty -> 1100,
        )
      }

      eventually()(aliceSplitwellClient.listBalanceUpdates(key) should have size 3)
      aliceSplitwellClient.listBalances(key) shouldBe Map(
        bobUserParty -> 1100,
        charlieUserParty -> -1100,
      )

      aliceSplitwellClient.net(
        key,
        Map(
          aliceUserParty -> Map(bobUserParty -> -1100, charlieUserParty -> 1100),
          bobUserParty -> Map(aliceUserParty -> 1100, charlieUserParty -> -1100),
          charlieUserParty -> Map(aliceUserParty -> -1100, bobUserParty -> 1100),
        ),
      )
      eventually() {
        aliceSplitwellClient.listBalances(key) shouldBe Map(
          bobUserParty -> 0,
          charlieUserParty -> 0,
        )
        bobSplitwellClient.listBalances(key) shouldBe Map(
          aliceUserParty -> 0,
          charlieUserParty -> -2200,
        )
        charlieSplitwellClient.listBalances(key) shouldBe Map(
          aliceUserParty -> 0,
          bobUserParty -> 2200,
        )
        splitwellWalletClient.listAppRewardCoupons() should have length 1
      }
    }
  }

  "be able to collect app payments across round changes" in { implicit env =>
    val (aliceUserParty, bobUserParty, _, _, key, _) =
      initSplitwellTest()

    aliceSplitwellClient.enterPayment(
      key,
      100.0,
      "team lunch",
    )
    bobWalletClient.tap(710)
    clue("Splitwell transfer with round change right after payment request") {

      bobSplitwellClient.initiateTransfer(
        key,
        Seq(
          new walletCodegen.ReceiverCCAmount(
            aliceUserParty.toProtoPrimitive,
            BigDecimal(50.0).bigDecimal,
          )
        ),
      )
      eventually()(bobWalletClient.listAppPaymentRequests() should not be empty)
      // TODO (#7609): replace with stopping and starting triggers
      splitwellBackend.stop() // to avoid the automation triggering before the round change
      inside(bobWalletClient.listAppPaymentRequests()) { case Seq(request) =>
        bobWalletClient.acceptAppPaymentRequest(request.appPaymentRequest.contractId)
      }
      eventually()(bobWalletClient.listAppPaymentRequests() shouldBe empty)
      advanceRoundsByOneTick
      splitwellBackend.start()
    }
    eventually() {
      advanceTimeByPollingInterval(splitwellBackend)
      aliceSplitwellClient.listBalanceUpdates(key) should have size 2
    }
    aliceSplitwellClient.listBalances(key) shouldBe Seq(bobUserParty -> 0).toMap
  }
}
