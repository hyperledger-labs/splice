// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.automation

import com.daml.network.config.AutomationConfig
import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.time.Clock

/** Convenience class to capture the shared context required to instantiate triggers in an automation service. */
final case class TriggerContext(
    config: AutomationConfig,
    clock: Clock,
    pollingClock: Clock,
    triggerEnabledSync: TriggerEnabledSynchronization,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
    metricsFactory: LabeledMetricsFactory,
) {
  def timeouts: ProcessingTimeout = retryProvider.timeouts
}
