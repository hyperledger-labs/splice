package com.daml.network.wallet.config

import com.daml.network.config.RemoteCoinConfig
import com.digitalasset.canton.config.ClientConfig

case class RemoteWalletAppConfig(
  adminApi: ClientConfig,
) extends RemoteCoinConfig {
  override def clientAdminApi: ClientConfig = adminApi
}
