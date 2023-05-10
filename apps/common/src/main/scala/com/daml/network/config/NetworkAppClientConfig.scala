package com.daml.network.config

import akka.http.scaladsl.model.Uri
import com.digitalasset.canton.config.{KeepAliveClientConfig, TlsClientConfig}

/** A client configuration to a corresponding server configuration
  *  @see [[com.digitalasset.canton.config.ClientConfig]]
  */
case class NetworkAppClientConfig(
    url: Uri,
    tls: Option[TlsClientConfig] = None,
    keepAliveClient: Option[KeepAliveClientConfig] = Some(KeepAliveClientConfig()),
)

trait NetworkAppNodeConfig {
  def clientAdminApi: NetworkAppClientConfig
}
