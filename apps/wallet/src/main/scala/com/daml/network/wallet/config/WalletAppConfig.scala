package com.daml.network.wallet.config

import com.daml.network.config.{HttpCNNodeClientConfig, NetworkAppClientConfig}
import com.digitalasset.canton.DomainAlias

case class WalletDomainConfig(
    global: DomainAlias
)

// Inlined to avoid a dependency
case class WalletValidatorAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

case class WalletAppClientConfig(
    adminApi: NetworkAppClientConfig,
    ledgerApiUser: String,
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
