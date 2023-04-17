package com.daml.network.config

import akka.http.scaladsl.model.Uri
import com.digitalasset.canton.config.ClientConfig

object CNHttpClientConfig {

  /** Implicit augmentation of ClientConfig with HTTP-relevant helper methods */
  implicit class CNHttpClientConfig(
      clientConfig: ClientConfig
  ) {
    def url: String = Uri(clientConfig.address).withPort(clientConfig.port.unwrap).toString()
  }
}
