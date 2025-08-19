// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.lfdecentralizedtrust.splice.util.SpliceRateLimitConfig

case class RateLimitersConfig(
    default: SpliceRateLimitConfig,
    rateLimiters: Map[String, SpliceRateLimitConfig],
) {
  def forRateLimiter(name: String): SpliceRateLimitConfig = rateLimiters.getOrElse(name, default)
}
