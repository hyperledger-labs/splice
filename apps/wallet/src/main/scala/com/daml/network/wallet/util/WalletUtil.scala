package com.daml.network.wallet.util

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.util.UploadablePackage

object WalletUtil extends UploadablePackage {
  lazy val walletTemplateId: com.daml.ledger.api.v1.value.Identifier =
    ApiTypes.TemplateId.unwrap(walletCodegen.AppPaymentRequest.id)

  lazy val packageId: String = walletTemplateId.packageId

  // See `Compile / resourceGenerators` in build.sbt
  lazy val resourcePath: String = "dar/wallet-0.1.0.dar"
}
