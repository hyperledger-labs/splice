package com.daml.network.config

import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.config.{
  ClientConfig,
  KeepAliveClientConfig,
  NonNegativeDuration,
  TlsClientConfig,
}
import scala.concurrent.duration.*

/** Extension of ClientConfig that supports specifying a URL
  * which is used for http requests.
  */
case class CNHttpClientConfig(
    address: String = "127.0.0.1",
    port: Port,
    url: String,
    tls: Option[TlsClientConfig] = None,
    keepAliveClient: Option[KeepAliveClientConfig] = Some(KeepAliveClientConfig()),
    healthStatusTimeout: NonNegativeDuration = CNHttpClientConfig.defaultHealthStatusTimeout,
    healthStatusMaxBackoff: NonNegativeDuration = CNHttpClientConfig.defaultHealthStatusMaxBackoff,
) {

  def clientConfig: ClientConfig = ClientConfig(
    address,
    port,
    tls,
    keepAliveClient,
  )
}

object CNHttpClientConfig {
  def fromClientConfig(url: String, config: ClientConfig): CNHttpClientConfig =
    CNHttpClientConfig(
      config.address,
      config.port,
      url,
      config.tls,
      config.keepAliveClient,
    )
  private val defaultHealthStatusTimeout: NonNegativeDuration =
    NonNegativeDuration.tryFromDuration(2.minute)
  private val defaultHealthStatusMaxBackoff: NonNegativeDuration =
    NonNegativeDuration.tryFromDuration(5.seconds)
}
