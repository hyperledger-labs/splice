package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.digitalasset.canton.console.RemoteParticipantReference
import com.digitalasset.canton.participant.config.RemoteParticipantConfig

class ValidatorAppRemoteParticipantReference(
    consoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
    appName: String,
) extends RemoteParticipantReference(consoleEnvironment, name) {
  override def config: RemoteParticipantConfig =
    consoleEnvironment.environment.config.validatorsByString(appName).remoteParticipant
}

class SvcAppRemoteParticipantReference(
    consoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
    appName: String,
) extends RemoteParticipantReference(consoleEnvironment, name) {
  override def config: RemoteParticipantConfig =
    consoleEnvironment.environment.config.svcsByString(appName).remoteParticipant
}
