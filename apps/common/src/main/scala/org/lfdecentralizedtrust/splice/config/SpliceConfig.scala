// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.*
import com.digitalasset.canton.environment.CantonNodeParameters
import com.digitalasset.canton.sequencing.client.SequencerClientConfig
import com.digitalasset.canton.time.EnrichedDurations.*
import com.digitalasset.canton.tracing.TracingConfig

/** Abstraction to remove code duplication when implementing Canton traits and specifying parameters we don't use
  * anyway.
  */
abstract class SpliceBackendConfig extends LocalNodeConfig {
  override val init: InitConfig = InitConfig()
  override val crypto: CryptoConfig = CommunityCryptoConfig()
  override val sequencerClient: SequencerClientConfig = SequencerClientConfig()
  override val topology: TopologyConfig = TopologyConfig()

  override val monitoring: NodeMonitoringConfig = NodeMonitoringConfig()
  def participantClient: ParticipantClientConfig
  def automation: AutomationConfig
}

/** Abstraction to remove code duplication when implementing Canton traits and specifying parameters we don't use
  * anyway.
  */
abstract class GrpcClientConfig extends NodeConfig {}
abstract class HttpClientConfig extends NetworkAppNodeConfig {}

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
    override val tracing: TracingConfig,
    delayLoggingThreshold_ : NonNegativeFiniteDuration,
    override val loggingConfig: LoggingConfig,
    override val logQueryCost: Option[QueryCostMonitoringConfig],
    override val processingTimeouts: ProcessingTimeout,
    requestTimeout: NonNegativeDuration,
    upgradesConfig: UpgradesConfig = UpgradesConfig(),
    // TODO(#736): likely remove all of the following:
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

  override val delayLoggingThreshold = delayLoggingThreshold_.toInternal

  override val exitOnFatalFailures: Boolean = true

  override def useUnifiedSequencer: Boolean = false

  override def watchdog: Option[WatchdogConfig] = None
}
