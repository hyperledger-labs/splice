// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton

import com.digitalasset.canton.config.SharedCantonConfig
import com.digitalasset.canton.environment.Environment

package object integration {

  /** This type takes the console type used at runtime for the environment and then augments it with
    * a type supporting our typical integration test extensions.
    */
  type TestConsoleEnvironment[C <: SharedCantonConfig[C], E <: Environment] = E#Console
    with TestEnvironment[C, E]
}
