package com.daml.network.automation

import com.daml.network.config.AutomationConfig
import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock

/** Convenience class to capture the shared context required to instantiate triggers in an automation service. */
case class TriggerContext(
    config: AutomationConfig,
    timeouts: ProcessingTimeout,
    clock: Clock,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
)
