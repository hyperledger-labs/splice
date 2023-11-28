package com.daml.network.config

import org.apache.pekko.http.scaladsl.model.Uri
import com.digitalasset.canton.config.{KeepAliveClientConfig, TlsClientConfig}

/** A client configuration to a corresponding server configuration
  *  @see [[com.digitalasset.canton.config.ClientConfig]]
  */
case class NetworkAppClientConfig(
    url: Uri,
    tls: Option[TlsClientConfig] = None,
    keepAliveClient: Option[KeepAliveClientConfig] = Some(KeepAliveClientConfig()),
    // whether to fail hard (on the client side) or just log when the app versions of the client and server do not match
    failOnVersionMismatch: Boolean = true,
)

trait NetworkAppNodeConfig {
  def clientAdminApi: NetworkAppClientConfig
}
