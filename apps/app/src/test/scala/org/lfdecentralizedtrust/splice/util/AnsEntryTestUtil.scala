package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.console.{AnsExternalAppReference, WalletAppClientReference}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import com.digitalasset.canton.topology.PartyId

trait AnsEntryTestUtil extends TestCommon with AnsTestUtil {
  this: CommonAppInstanceReferences =>
  def initialiseAnsEntry(
      userName: String,
      userParty: PartyId,
      ansExternalApp: AnsExternalAppReference,
      wallet: WalletAppClientReference,
  ): Unit = {
    val (_, reqId) = actAndCheck(
      show"Request ANS entry ${userName.singleQuoted} for $userParty",
      ansExternalApp.createAnsEntry(
        userName,
        testEntryUrl,
        testEntryDescription,
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
