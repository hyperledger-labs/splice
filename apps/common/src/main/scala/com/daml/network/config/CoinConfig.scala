package com.daml.network.config

import com.digitalasset.canton.config.{
  CachingConfigs,
  ClockConfig,
  CommunityCryptoConfig,
  CryptoConfig,
  InitConfig,
  LocalNodeConfig,
  LocalNodeParameters,
  LoggingConfig,
  NodeConfig,
  ProcessingTimeout,
  QueryCostMonitoringConfig,
}
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.sequencing.client.SequencerClientConfig
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TracingConfig
import com.digitalasset.canton.version.ProtocolVersion

/** Abstraction to remove code duplication when implementing Canton traits and specifying parameters we don't use
  * anyway.
  */
abstract class LocalCoinConfig extends LocalNodeConfig {
  override val init: InitConfig = InitConfig()
  override val crypto: CryptoConfig = CommunityCryptoConfig()
  override val sequencerClient: SequencerClientConfig = SequencerClientConfig()
  override val caching: CachingConfigs = CachingConfigs()
  def remoteParticipant: RemoteParticipantConfig
}

/** Abstraction to remove code duplication when implementing Canton traits and specifying parameters we don't use
  * anyway.
  */
abstract class RemoteCoinConfig extends NodeConfig {}

/** This class aggregates binary-level configuration options that are shared between each CN app instance.
  * For example, the [[TracingConfig]] is configured once for all CN apps that are started by a CN binary as part of the
  * [[com.digitalasset.canton.config.MonitoringConfig]].
  * To avoid having to pass the configuration options for all apps (as implemented in [[com.daml.network.config.CoinConfig]])
  * to a single app, a [[SharedCoinAppParameters]] class instance is constructed and passed to each app during bootstrapping.
  *
  * An exception to this are the [[SequencerClientConfig]] and [[CachingConfigs]]. These are configured on a per-node-level
  * but are still in the [[SharedCoinAppParameters]]. They will be removed in the future.
  * These two parameters are also the reason why the [[SharedCoinAppParameters]] are created individually for each configured
  * SVC/Validator/Wallet app in, e.g., `svcAppParameters_` of [[com.daml.network.config.CoinConfig]] (instead of just
  * creating a single [[SharedCoinAppParameters]] instance once and passing that instance to all apps).
  */
case class SharedCoinAppParameters(
    override val tracing: TracingConfig,
    override val delayLoggingThreshold: NonNegativeFiniteDuration,
    override val loggingConfig: LoggingConfig,
    override val logQueryCost: Option[QueryCostMonitoringConfig],
    override val processingTimeouts: ProcessingTimeout,
    // TODO(#736): likely remove all of the following:
    override val cachingConfigs: CachingConfigs,
    override val enableAdditionalConsistencyChecks: Boolean,
    override val enablePreviewFeatures: Boolean,
    override val nonStandardConfig: Boolean,
    override val sequencerClient: SequencerClientConfig,
    override val devVersionSupport: Boolean,
    override val dontWarnOnDeprecatedPV: Boolean,
    override val initialProtocolVersion: ProtocolVersion,
    val clockConfig: ClockConfig,
) extends LocalNodeParameters
