package com.daml.network.config

import com.digitalasset.canton.config.ClientConfig

/** @param clientConfig Connection parameters
  * @param authConfig Auth tokens used by the app
  */
case class CoinLedgerApiClientConfig(
    clientConfig: ClientConfig,
    authConfig: AuthTokenSourceConfig,
)
