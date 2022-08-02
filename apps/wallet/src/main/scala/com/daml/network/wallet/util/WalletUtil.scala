package com.daml.network.wallet.util

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.util.UploadablePackage
import com.digitalasset.network.CN.Wallet.PaymentRequest

object WalletUtil extends UploadablePackage {
  lazy val walletTemplateId: com.daml.ledger.api.v1.value.Identifier =
    ApiTypes.TemplateId.unwrap(PaymentRequest.id)

  lazy val packageId: String = walletTemplateId.packageId

  // See `Compile / resourceGenerators` in build.sbt
  lazy val resourcePath: String = "dar/wallet.dar"
}
