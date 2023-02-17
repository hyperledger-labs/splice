package com.daml.network.util

import com.daml.network.integration.tests.CoinTests.{CoinTestCommon, CoinTestConsoleEnvironment}
import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.console.SplitwellAppClientReference
import com.daml.network.console.WalletAppClientReference
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.time.RemoteClock

import java.time.Duration
import org.slf4j.event.Level

trait SplitwellTestUtil extends CoinTestCommon with WalletTestUtil with TimeTestUtil {
  def initSplitwellTest()(implicit
      env: CoinTestConsoleEnvironment
  ) = {
    val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
    val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
    val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
    // The provider's wallet is auto-onboarded, so we just need to wait for it to be ready
    waitForWalletUser(splitwellProviderWallet)

    // TODO(#2871) Remove this again once the transfer no longer ends up picking the wrong
    // topology time.
    if (isSimTime()) {
      advanceTime(Duration.ofSeconds(30))
    }

    val splitwellProviderParty = providerSplitwellBackend.getProviderPartyId()

    // Setup install contracts
    Seq(
      (aliceSplitwell, aliceUserParty),
      (bobSplitwell, bobUserParty),
      (charlieSplitwell, charlieUserParty),
    ).foreach { case (splitwell, party) =>
      splitwell.createInstallRequest()
      splitwell.ledgerApi.ledger_api_extensions.acs
        .awaitJava(splitwellCodegen.SplitwellInstall.COMPANION)(party)
    }

    aliceSplitwell.requestGroup("group1")
    eventually() {
      aliceSplitwell.listGroups() should have size 1
    }
    val invite = aliceSplitwell.createGroupInvite(
      "group1"
    )

    bobSplitwell.acceptInvite(invite)

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

    (aliceUserParty, bobUserParty, charlieUserParty, splitwellProviderParty, key, invite)
  }

  private def isSimTime()(implicit env: CoinTestConsoleEnvironment): Boolean =
    env.environment.clock match {
      case _: RemoteClock =>
        true
      case _ => false
    }

  private def syncOnTransfers[A](numTransfers: Int, act: => A)(implicit
      env: CoinTestConsoleEnvironment
  ) =
    if (isSimTime()) {
      // TODO(#2864) Remove workarounds for buggy transfers in simtime. For now, we need to
      // advance time manually after the transfer out has been submitted.
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
        act,
        forExactly(numTransfers, _)(_.message should include("Submitting transfer out")),
      )
      advanceTime(Duration.ofSeconds(5))
    } else {
      act
    }

  def splitwellTransfer(
      senderSplitwell: SplitwellAppClientReference,
      senderWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      key: GrpcSplitwellAppClient.GroupKey,
  )(implicit env: CoinTestConsoleEnvironment) = {
    syncOnTransfers(
      2,
      senderSplitwell.initiateTransfer(
        key,
        Seq(
          new walletCodegen.ReceiverCCAmount(
            receiver.toProtoPrimitive,
            amount.bigDecimal,
          )
        ),
      ),
    )
    eventually()(senderWallet.listAppPaymentRequests() should not be empty)
    inside(senderWallet.listAppPaymentRequests()) { case Seq(request) =>
      syncOnTransfers(1, senderWallet.acceptAppPaymentRequest(request.contractId))
    }

  }

}
