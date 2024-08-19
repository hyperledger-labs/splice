// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.config

import com.digitalasset.canton.config.NonNegativeFiniteDuration

import java.time.Duration

/** Configuration for the Ledger API Interactive Submission Service.
  *
  * @param enabled
  *        if false (default), the interactive submission service is disabled.
  */
final case class InteractiveSubmissionServiceConfig(
    enabled: Boolean = false,
    // Temporary config while the prepare commands are cached in memory
    // TODO(i20725): remove when interactive submissions are stateless
    prepareCacheTTL: NonNegativeFiniteDuration = NonNegativeFiniteDuration(
      Duration.ofHours(1)
    ),
)

object InteractiveSubmissionServiceConfig {
  lazy val Default: InteractiveSubmissionServiceConfig = InteractiveSubmissionServiceConfig()
}
