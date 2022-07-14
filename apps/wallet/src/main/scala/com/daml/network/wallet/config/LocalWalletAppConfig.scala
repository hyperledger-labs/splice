package com.daml.network.wallet.config

import com.daml.network.config.LocalCoinConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.sequencing.client.SequencerClientConfig
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TracingConfig

case class LocalWalletAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    // TODO(i142): slightly different in Canton
    parameters: WalletAppParameters = WalletAppParameters(),
    remoteParticipant: RemoteParticipantConfig,
) extends LocalCoinConfig // TODO(142): fork or generalize this trait.
    {
  override val nodeTypeName: String = "wallet"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

case class WalletAppParameters(
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
