package com.daml.network.validator.config

import com.digitalasset.canton.config.{
  CachingConfigs,
  ClientConfig,
  CommunityAdminServerConfig,
  CommunityStorageConfig,
  CryptoConfig,
  InitConfig,
  LocalNodeConfig,
  LocalNodeParameters,
  LoggingConfig,
  ProcessingTimeout,
  QueryCostMonitoringConfig,
}
import com.digitalasset.canton.participant.config.{
  LedgerApiServerConfig,
  ParticipantNodeParameterConfig,
  RemoteParticipantConfig,
  TestingTimeServiceConfig,
}
import com.digitalasset.canton.sequencing.client.SequencerClientConfig
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TracingConfig

case class LocalValidatorConfig(
    override val init: InitConfig = InitConfig(),
    override val crypto: CryptoConfig = CryptoConfig(),
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    // TODO(i142): slightly different in Canton
    parameters: ValidatorNodeParameters = ValidatorNodeParameters(),
    override val sequencerClient: SequencerClientConfig = SequencerClientConfig(),
    override val caching: CachingConfigs = CachingConfigs(),
    remoteParticipant: RemoteParticipantConfig,
) extends LocalNodeConfig // TODO(142): fork or generalize this trait.
    {
  override val nodeTypeName: String = "validator"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class ValidatorNodeParameters(
    override val tracing: TracingConfig = TracingConfig(),
    override val delayLoggingThreshold: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(20),
    override val loggingConfig: LoggingConfig = LoggingConfig(),
    override val logQueryCost: Option[QueryCostMonitoringConfig] = None,
    override val processingTimeouts: ProcessingTimeout = ProcessingTimeout(),
    // TODO(i87): likely remove all of the following:
    override val cachingConfigs: CachingConfigs = CachingConfigs(),
    override val enableAdditionalConsistencyChecks: Boolean = false,
    override val enablePreviewFeatures: Boolean = false,
    override val nonStandardConfig: Boolean = false,
    override val sequencerClient: SequencerClientConfig = SequencerClientConfig(),
    override val devVersionSupport: Boolean = false,
) extends LocalNodeParameters
