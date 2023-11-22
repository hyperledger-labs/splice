package com.daml.network.util

import com.digitalasset.canton.util.ShowUtil.*
import com.daml.network.console.{DirectoryExternalAppReference, WalletAppClientReference}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.digitalasset.canton.topology.PartyId

trait DirectoryTestUtil extends CNNodeTestCommon with CnsTestUtil {
  this: CommonCNNodeAppInstanceReferences =>
  def initialiseDirectoryApp(
      userName: String,
      userParty: PartyId,
      directoryExternalApp: DirectoryExternalAppReference,
      wallet: WalletAppClientReference,
  ): Unit = {
    val (_, reqId) = actAndCheck(
      show"Request directory entry ${userName.singleQuoted} for $userParty",
      directoryExternalApp.createDirectoryEntry(
        userName,
        "https://cns-dir-url.com",
        "Sample CNS Directory Entry Description",
      ),
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
