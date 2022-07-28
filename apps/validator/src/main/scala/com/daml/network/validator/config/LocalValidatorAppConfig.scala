package com.daml.network.validator.config

import com.daml.network.config.LocalCoinConfig
import com.digitalasset.canton.config._
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

case class LocalValidatorAppConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: CommunityStorageConfig = CommunityStorageConfig.Memory(),
    damlUser: String = "validator",
    remoteParticipant: RemoteParticipantConfig,
) extends LocalCoinConfig // TODO(142): fork or generalize this trait.
    {
  override val nodeTypeName: String = "validator"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}
