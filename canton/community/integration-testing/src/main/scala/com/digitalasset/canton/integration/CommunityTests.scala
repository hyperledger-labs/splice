// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.integration

import com.digitalasset.canton.config.{CantonConfig, CantonEdition, CommunityCantonEdition}
import com.digitalasset.canton.environment.CommunityEnvironment

object CommunityTests {

  type CommunityTestConsoleEnvironment = TestConsoleEnvironment[CantonConfig, CommunityEnvironment]

  trait CommunityIntegrationTest
      extends BaseIntegrationTest[
        CantonConfig,
        CommunityEnvironment,
        CommunityTestConsoleEnvironment,
      ] {
    this: EnvironmentSetup[CantonConfig, CommunityEnvironment, CommunityTestConsoleEnvironment] =>

    override val edition: CantonEdition = CommunityCantonEdition
  }

  type SharedCommunityEnvironment =
    SharedEnvironment[CantonConfig, CommunityEnvironment, CommunityTestConsoleEnvironment]
  type IsolatedCommunityEnvironments =
    IsolatedEnvironments[CantonConfig, CommunityEnvironment, CommunityTestConsoleEnvironment]

}
