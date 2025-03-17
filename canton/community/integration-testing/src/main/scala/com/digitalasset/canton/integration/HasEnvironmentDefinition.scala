// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.integration

import com.digitalasset.canton.config.SharedCantonConfig
import com.digitalasset.canton.environment.Environment

/** Trait for exposing only an environment definition */
trait HasEnvironmentDefinition[C <: SharedCantonConfig[
  C
], E <: Environment, TCE <: TestConsoleEnvironment[C, E]] {
  def environmentDefinition: BaseEnvironmentDefinition[C, E, TCE]
}
