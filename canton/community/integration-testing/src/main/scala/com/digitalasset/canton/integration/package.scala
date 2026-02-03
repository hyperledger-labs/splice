// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton

import com.digitalasset.canton.config.{CantonConfig, SharedCantonConfig, StorageConfig}
import com.digitalasset.canton.environment.Environment
import com.digitalasset.canton.integration.ConfigTransforms.ConfigNodeType

package object integration {
  type TestConsoleEnvironment[C <: SharedCantonConfig[C], E <: Environment[C]] = E#Console
    with TestEnvironment[C]
  type ConfigTransform = CantonConfig => CantonConfig
  type StorageConfigTransform =
    (ConfigNodeType, String, StorageConfig) => StorageConfig
}
