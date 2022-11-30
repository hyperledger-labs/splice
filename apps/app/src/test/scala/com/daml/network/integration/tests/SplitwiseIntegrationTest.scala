package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.{splitwise => splitwiseCodegen}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.splitwise.admin.api.client.commands.GrpcSplitwiseAppClient
import com.daml.network.util.CoinTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.Future

class SplitwiseIntegrationTest extends CoinIntegrationTest with CoinTestUtil {

  private val darPath = "apps/splitwise/daml/.daml/dist/splitwise-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwise" should {
    "restart cleanly" in { implicit env =>
      providerSplitwiseBackend.stop()
      providerSplitwiseBackend.startSync()
    }

    "support provider-hosted mode" in { implicit env =>
      // Onboard users
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)
      val charlieUserParty = onboardWalletUser(this, charlieWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(this, bobWallet, bobValidator)

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

      eventually() { bobSplitwise.listGroupInvites() should not be empty }
      inside(bobSplitwise.listGroupInvites()) { case Seq(invite) =>
        bobSplitwise.acceptInvite(invite.contractId)
      }

      eventually() { aliceSplitwise.listAcceptedGroupInvites("group1") should not be empty }
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
        42.0,
        "payment",
      )
      bobSplitwise.initiateTransfer(
        key,
        Seq(
          new walletCodegen.ReceiverCCQuantity(
            aliceUserParty.toProtoPrimitive,
            BigDecimal(10.0).bigDecimal,
          )
        ),
      )
      eventually()(bobWallet.listAppPaymentRequests() should not be empty)
      inside(bobWallet.listAppPaymentRequests()) { case Seq(request) =>
        bobWallet.tap(20)
        bobWallet.acceptAppPaymentRequest(request.contractId)
      }
      eventually() {
        bobSplitwise.listBalanceUpdates(key) should have size 2
      }
      bobSplitwise.listBalances(key) shouldBe Seq(aliceUserParty -> -11).toMap

      aliceSplitwise.listBalanceUpdates(key) should have size 2
      aliceSplitwise.listBalances(key) shouldBe Seq(bobUserParty -> 11).toMap

      inside(charlieSplitwise.listGroupInvites()) { case Seq(invite) =>
        charlieSplitwise.acceptInvite(invite.contractId)
      }
      eventually() {
        aliceSplitwise.listAcceptedGroupInvites("group1") should have size 1
      }
      inside(aliceSplitwise.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwise.joinGroup(accepted.contractId)
      }

      splitwiseValidator.remoteParticipant.ledger_api.acs
        .awaitJava(splitwiseCodegen.Group.COMPANION)(providerSplitwiseBackend.getProviderPartyId())

      charlieSplitwise.listBalances(key) shouldBe Map.empty
      charlieSplitwise.enterPayment(key, 33.0, "payment")
      eventually() {
        charlieSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 11, bobUserParty -> 11)
      }

      eventually()(aliceSplitwise.listBalanceUpdates(key) should have size 3)
      aliceSplitwise.listBalances(key) shouldBe Map(bobUserParty -> 11, charlieUserParty -> -11)

      aliceSplitwise.net(
        key,
        Map(
          aliceUserParty -> Map(bobUserParty -> -11, charlieUserParty -> 11),
          bobUserParty -> Map(aliceUserParty -> 11, charlieUserParty -> -11),
          charlieUserParty -> Map(aliceUserParty -> -11, bobUserParty -> 11),
        ),
      )
      eventually() {
        aliceSplitwise.listBalances(key) shouldBe Map(bobUserParty -> 0, charlieUserParty -> 0)
      }
      eventually() {
        bobSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, charlieUserParty -> -22)
      }
      eventually() {
        charlieSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, bobUserParty -> 22)
      }
    }

    "allocate unique groups per party, even when multiple requests race for them" in {
      implicit env =>
        import env._

        val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

        aliceSplitwise.createInstallRequest()
        aliceSplitwise.ledgerApi.ledger_api.acs
          .awaitJava(splitwiseCodegen.SplitwiseInstall.COMPANION)(aliceUserParty)

        def createGroup() = {
          val groupRequest = aliceSplitwise.requestGroup("group1")

          // Wait for request to be archived and therefore either the group to be created or
          // the request to be rejected.
          eventually() {
            aliceSplitwise.ledgerApi.ledger_api.acs
              .filterJava(splitwiseCodegen.GroupRequest.COMPANION)(
                aliceUserParty,
                (request: splitwiseCodegen.GroupRequest.Contract) => request.id == groupRequest,
              ) shouldBe empty
          }
        }

        // Concurrently, create two groups with the same id
        val group1 = Future {
          createGroup()
        }
        val group2 = Future {
          createGroup()
        }

        // Wait for both of them
        group1.futureValue
        group2.futureValue

        // We read directly from the ledger API to avoid having to synchronize on the store.
        val groups =
          aliceSplitwise.ledgerApi.ledger_api.acs
            .filterJava(splitwiseCodegen.Group.COMPANION)(aliceUserParty)
        groups should have size 1
    }

    "return the primary party of the user" in { implicit env =>
      val user = providerSplitwiseBackend.remoteParticipant.ledger_api.users
        .get(providerSplitwiseBackend.config.providerUser)
      Some(providerSplitwiseBackend.getProviderPartyId().toLf) shouldBe user.primaryParty
    }
  }
}
