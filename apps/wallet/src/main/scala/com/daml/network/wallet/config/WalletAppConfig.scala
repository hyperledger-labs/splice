package com.daml.network.wallet.config

import com.digitalasset.canton.DomainAlias
import com.daml.network.config.{CNHttpClientConfig, RemoteCNNodeConfig}
import com.digitalasset.canton.config.*

case class WalletDomainConfig(
    global: DomainAlias
)

// Inlined to avoid a dependency
case class WalletRemoteValidatorAppConfig(
    adminApi: CNHttpClientConfig
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}

case class WalletAppClientConfig(
    adminApi: CNHttpClientConfig,
    ledgerApiUser: String,
) extends RemoteCNNodeConfig {
  override def clientAdminApi: ClientConfig = adminApi.clientConfig
}
