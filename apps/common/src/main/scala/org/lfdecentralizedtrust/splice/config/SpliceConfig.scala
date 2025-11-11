// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.StartupMemoryCheckConfig.ReportingLevel.Warn
import com.digitalasset.canton.environment.CantonNodeParameters
import com.digitalasset.canton.sequencing.client.SequencerClientConfig
import com.digitalasset.canton.tracing.TracingConfig

/** Abstraction to remove code duplication when implementing Canton traits and specifying parameters we don't use
  * anyway.
  */
abstract class SpliceBackendConfig extends LocalNodeConfig {
  override val init: InitConfig = InitConfig()
  override val sequencerClient: SequencerClientConfig = SequencerClientConfig()

  override def crypto: CryptoConfig = CryptoConfig()

  override val topology: TopologyConfig = TopologyConfig()

  override val monitoring: NodeMonitoringConfig = NodeMonitoringConfig()

  def participantClient: BaseParticipantClientConfig
  def automation: AutomationConfig

}

/** Abstraction to remove code duplication when implementing Canton traits and specifying parameters we don't use
  * anyway.
  */
abstract class GrpcClientConfig extends NodeConfig {}
abstract class HttpClientConfig extends NetworkAppNodeConfig {}

final case class CircuitBreakerConfig(
    maxFailures: Int = 20,
    callTimeout: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(0), // disable timeout
    resetTimeout: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    maxResetTimeout: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
    exponentialBackoffFactor: Double = 2.0,
    randomFactor: Double = 0.2,
    // If the last failure was more than resetFailuresAfter ago, reset the failures to 0.
    resetFailuresAfter: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(15),
)

final case class CircuitBreakersConfig(
    highPriority: CircuitBreakerConfig = CircuitBreakerConfig(
      maxResetTimeout = NonNegativeFiniteDuration.ofMinutes(2)
    ),
    mediumPriority: CircuitBreakerConfig = CircuitBreakerConfig(
      maxFailures = 10,
      maxResetTimeout = NonNegativeFiniteDuration.ofMinutes(3),
    ),
    lowPriority: CircuitBreakerConfig = CircuitBreakerConfig(
      maxFailures = 5,
      maxResetTimeout = NonNegativeFiniteDuration.ofMinutes(7),
    ),
    // Amulet expiry is different from essentially any other trigger run in the SV app in that for it to complete successfully
    // we need a confirmation from the node hosting the amulet owner. So in other words, if a node is down
    // this will start failing. Therefore, we use a dedicated circuit breaker just for amulet expiry
    // to avoid this causing issues for other triggers.
    amuletExpiry: CircuitBreakerConfig = CircuitBreakerConfig(
      maxFailures = 5,
      maxResetTimeout = NonNegativeFiniteDuration.ofMinutes(7),
    ),
)

final case class EnabledFeaturesConfig(
    enableNewAcsExport: Boolean = true,
    newSequencerConnectionPool: Boolean = true,
)

/** This class aggregates binary-level configuration options that are shared between each Splice app instance.
  * For example, the [[TracingConfig]] is configured once for all Splice apps that are started by a Splice binary as part of the
  * [[com.digitalasset.canton.config.MonitoringConfig]].
  * To avoid having to pass the configuration options for all apps (as implemented in [[org.lfdecentralizedtrust.splice.config.SpliceConfig]])
  * to a single app, a [[SharedSpliceAppParameters]] class instance is constructed and passed to each app during bootstrapping.
  *
  * An exception to this are the [[SequencerClientConfig]] and [[CachingConfigs]]. These are configured on a per-node-level
  * but are still in the [[SharedSpliceAppParameters]]. They will be removed in the future.
  * These two parameters are also the reason why the [[SharedSpliceAppParameters]] are created individually for each configured
  * SV/Validator app in, e.g., `svAppParameters_` of [[org.lfdecentralizedtrust.splice.config.SpliceConfig]] (instead of just
  * creating a single [[SharedSpliceAppParameters]] instance once and passing that instance to all apps).
  */
case class SharedSpliceAppParameters(
    monitoringConfig: MonitoringConfig,
    override val processingTimeouts: ProcessingTimeout,
    requestTimeout: NonNegativeDuration,
    upgradesConfig: UpgradesConfig = UpgradesConfig(),
    circuitBreakers: CircuitBreakersConfig = CircuitBreakersConfig(),
    enabledFeatures: EnabledFeaturesConfig = EnabledFeaturesConfig(),
    // TODO(DACH-NY/canton-network-node#736): likely remove all of the following:
    override val cachingConfigs: CachingConfigs,
    override val enableAdditionalConsistencyChecks: Boolean,
    override val enablePreviewFeatures: Boolean,
    override val nonStandardConfig: Boolean,
    override val sequencerClient: SequencerClientConfig,
    override val dontWarnOnDeprecatedPV: Boolean,
    override val dbMigrateAndStart: Boolean,
    override val batchingConfig: BatchingConfig,
) extends CantonNodeParameters {
  override def alphaVersionSupport: Boolean = false
  override def betaVersionSupport: Boolean = false

  override def tracing: TracingConfig = monitoringConfig.tracing

  override def loggingConfig: LoggingConfig = monitoringConfig.logging

  override val delayLoggingThreshold = loggingConfig.delayLoggingThreshold.toInternal

  override val exitOnFatalFailures: Boolean = true

  override def watchdog: Option[WatchdogConfig] = None

  override def startupMemoryCheckConfig: StartupMemoryCheckConfig = StartupMemoryCheckConfig(Warn)
}
