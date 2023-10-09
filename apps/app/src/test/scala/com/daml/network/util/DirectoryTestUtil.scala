package com.daml.network.util

import com.digitalasset.canton.util.ShowUtil.*
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.console.{DirectoryAppClientReference, WalletAppClientReference}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.digitalasset.canton.topology.PartyId

trait DirectoryTestUtil extends CNNodeTestCommon with CnsTestUtil {
  this: CommonCNNodeAppInstanceReferences =>
  def initialiseDirectoryApp(
      userName: String,
      userParty: PartyId,
      directory: DirectoryAppClientReference,
      wallet: WalletAppClientReference,
  ): Unit = {
    actAndCheck("Request directory install", directory.requestDirectoryInstall())(
      "Install created",
      _ =>
        directory.ledgerApi.ledger_api_extensions.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(userParty),
    )

    val (_, reqId) = actAndCheck(
      show"Request directory entry ${userName.singleQuoted} for $userParty",
      directory.requestDirectoryEntry(
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
