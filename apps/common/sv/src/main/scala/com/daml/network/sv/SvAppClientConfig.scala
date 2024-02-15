package com.daml.network.sv

import com.daml.network.config.{HttpCNNodeClientConfig, NetworkAppClientConfig}

case class SvAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}
