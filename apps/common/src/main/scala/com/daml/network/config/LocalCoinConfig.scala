package com.daml.network.config

import com.digitalasset.canton.config.{CachingConfigs, CryptoConfig, InitConfig, LocalNodeConfig}
import com.digitalasset.canton.sequencing.client.SequencerClientConfig

/** Abstraction to remove code duplication when implementing Canton traits and specifying parameters we don't use
  * anyway.
  */
abstract class LocalCoinConfig extends LocalNodeConfig {
  override val init: InitConfig = InitConfig()
  override val crypto: CryptoConfig = CryptoConfig()
  override val sequencerClient: SequencerClientConfig = SequencerClientConfig()
  override val caching: CachingConfigs = CachingConfigs()
}
