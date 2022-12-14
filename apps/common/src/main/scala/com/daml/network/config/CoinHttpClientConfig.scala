package com.daml.network.config

import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.config.{ClientConfig, KeepAliveClientConfig, TlsClientConfig}

/** Extension of ClientConfig that supports specifying a URL
  * which is used for http requests.
  */
case class CoinHttpClientConfig(
    address: String = "127.0.0.1",
    port: Port,
    url: String,
    tls: Option[TlsClientConfig] = None,
    keepAliveClient: Option[KeepAliveClientConfig] = Some(KeepAliveClientConfig()),
) {

  def clientConfig: ClientConfig = ClientConfig(
    address,
    port,
    tls,
    keepAliveClient,
  )
}

object CoinHttpClientConfig {
  def fromClientConfig(url: String, config: ClientConfig): CoinHttpClientConfig =
    CoinHttpClientConfig(
      config.address,
      config.port,
      url,
      config.tls,
      config.keepAliveClient,
    )
}
