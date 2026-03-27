// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.integration

import com.digitalasset.canton.config.CantonConfig
import com.digitalasset.canton.environment.{
  CantonEnvironment,
  CommunityEnvironmentFactory,
  EnvironmentFactory,
}

trait CommunityIntegrationTest extends BaseIntegrationTest[CantonConfig, CantonEnvironment] {
  this: EnvironmentSetup[CantonConfig, CantonEnvironment] =>

  override protected val environmentFactory: EnvironmentFactory[CantonConfig, CantonEnvironment] =
    CommunityEnvironmentFactory
}
