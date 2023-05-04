package com.daml.network.wallet.config

import com.daml.network.config.CNNodeClientConfig
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.*

case class WalletDomainConfig(
    global: DomainAlias
)

// Inlined to avoid a dependency
case class WalletValidatorAppClientConfig(
    adminApi: ClientConfig
) extends CNNodeClientConfig {
  override def clientAdminApi: ClientConfig = adminApi
}

case class WalletAppClientConfig(
    adminApi: ClientConfig,
    ledgerApiUser: String,
) extends CNNodeClientConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
