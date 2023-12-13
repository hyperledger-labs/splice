package com.daml.network.util

import com.digitalasset.canton.util.ShowUtil.*
import com.daml.network.console.{CnsExternalAppReference, WalletAppClientReference}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.digitalasset.canton.topology.PartyId

trait CnsEntryTestUtil extends CNNodeTestCommon with CnsTestUtil {
  this: CommonCNNodeAppInstanceReferences =>
  def initialiseCnsEntry(
      userName: String,
      userParty: PartyId,
      cnsExternalApp: CnsExternalAppReference,
      wallet: WalletAppClientReference,
  ): Unit = {
    val (_, reqId) = actAndCheck(
      show"Request CNS entry ${userName.singleQuoted} for $userParty",
      cnsExternalApp.createCnsEntry(
        userName,
        "https://cns-dir-url.com",
        "Sample CNS Entry Description",
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
