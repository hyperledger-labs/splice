package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.digitalasset.canton.console.RemoteParticipantReference
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

class CoinRemoteParticipantReference(
    consoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
    validatorName: String,
) extends RemoteParticipantReference(consoleEnvironment, name) {
  override def config: RemoteParticipantConfig =
    consoleEnvironment.environment.config.validatorsByString(validatorName).remoteParticipant
}
