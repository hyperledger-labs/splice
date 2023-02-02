package com.daml.network.util

import com.digitalasset.canton.util.ShowUtil.*
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.console.{RemoteDirectoryAppReference, WalletAppClientReference}
import com.daml.network.integration.tests.CoinTests.CoinTestCommon
import com.digitalasset.canton.topology.PartyId

trait DirectoryTestUtil extends CoinTestCommon with CnsTestUtil {
  this: CommonCoinAppInstanceReferences =>
  def initialiseDirectoryApp(
      userName: String,
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      wallet: WalletAppClientReference,
  ): Unit = {
    actAndCheck("Request directory install", directory.requestDirectoryInstall())(
      "Install created",
      _ =>
        directory.ledgerApi.ledger_api.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(userParty),
    )

    val (_, reqId) = actAndCheck(
      show"Request directory entry ${userName.singleQuoted} for $userParty",
      directory.requestDirectoryEntry(userName),
    )(
      "There is exactly one subscription request",
      _ => {
        inside(wallet.listSubscriptionRequests()) { case Seq(req) =>
          req.contractId
        }
      },
    )

    actAndCheck(
      "Tap and accept subscription request", {
        wallet.tap(5.0)
        wallet.acceptSubscriptionRequest(reqId)
      },
    )(
      "There are no subscription request left",
      _ => wallet.listSubscriptionRequests() should have length 0,
    )
  }
}
