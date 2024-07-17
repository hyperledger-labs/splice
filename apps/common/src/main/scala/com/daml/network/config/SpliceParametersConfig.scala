// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.config

import com.digitalasset.canton.config.{BatchingConfig, CachingConfigs, LocalNodeParametersConfig}

final case class SpliceParametersConfig(
    batching: BatchingConfig = BatchingConfig(),
    caching: CachingConfigs = CachingConfigs(),
) extends LocalNodeParametersConfig {
  override val useNewTrafficControl: Boolean =
    false // irrelevant for Splice, as this is an impl. config for Canton nodes only

  override def useUnifiedSequencer: Boolean = false

  override def devVersionSupport: Boolean = false
}
