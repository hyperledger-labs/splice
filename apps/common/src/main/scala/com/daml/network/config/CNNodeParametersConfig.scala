package com.daml.network.config

import com.digitalasset.canton.config.{BatchingConfig, CachingConfigs, LocalNodeParametersConfig}

final case class CNNodeParametersConfig(
    batching: BatchingConfig = BatchingConfig(),
    caching: CachingConfigs = CachingConfigs(),
) extends LocalNodeParametersConfig
