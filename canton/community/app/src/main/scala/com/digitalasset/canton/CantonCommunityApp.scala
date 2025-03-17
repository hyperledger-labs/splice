// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton

import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.config.{CantonConfig, CommunityCantonEdition}
import com.digitalasset.canton.environment.{
  CommunityEnvironment,
  CommunityEnvironmentFactory,
  EnvironmentFactory,
}

object CantonCommunityApp extends CantonAppDriver[CommunityEnvironment] {

  override type Config = CantonConfig

  override def loadConfig(
      config: com.typesafe.config.Config
  ): Either[CantonConfigError, CantonConfig] =
    CantonConfig.loadAndValidate(config, CommunityCantonEdition)

  override protected def environmentFactory
      : EnvironmentFactory[CantonConfig, CommunityEnvironment] =
    CommunityEnvironmentFactory

  override def withManualStart(config: CantonConfig): CantonConfig =
    config.copy(parameters = config.parameters.copy(manualStart = true))
}
