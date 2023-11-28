package com.daml.network.config

import com.digitalasset.canton.config.{BatchingConfig, LocalNodeParametersConfig}

final case class CNNodeParametersConfig(
    batching: BatchingConfig = BatchingConfig()
) extends LocalNodeParametersConfig
