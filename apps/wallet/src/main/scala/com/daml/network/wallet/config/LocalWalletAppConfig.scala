package com.daml.network.wallet.config

import com.daml.network.config.LocalCoinConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalWalletAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    remoteParticipant: RemoteParticipantConfig,
) extends LocalCoinConfig // TODO(142): fork or generalize this trait.
    {
  override val nodeTypeName: String = "wallet"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}
