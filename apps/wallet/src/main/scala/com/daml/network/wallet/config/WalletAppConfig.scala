package com.daml.network.wallet.config

import com.daml.network.config.RemoteCNNodeConfig
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.*

case class WalletDomainConfig(
    global: DomainAlias
)

// Inlined to avoid a dependency
case class WalletRemoteValidatorAppConfig(
    adminApi: ClientConfig
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi
}

case class WalletAppClientConfig(
    adminApi: ClientConfig,
    ledgerApiUser: String,
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
