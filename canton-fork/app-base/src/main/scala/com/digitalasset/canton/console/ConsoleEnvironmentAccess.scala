// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console

import io.opentelemetry.api.trace.Tracer

object ConsoleEnvironmentAccess {
  implicit class `ConsoleEnvironment access`(private val self: ConsoleEnvironment) extends AnyVal {
    def tracer: Tracer = self.tracer
  }
}
