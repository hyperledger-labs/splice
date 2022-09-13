package com.daml.network

import com.daml.network.config.CoinConfig
import com.daml.network.environment.{CoinEnvironmentFactory, CoinEnvironmentImpl}
import com.digitalasset.canton.CantonAppDriver
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.environment.EnvironmentFactory
import com.typesafe.config.Config

// TODO(i736): generalize. e.g. custom Cli class for Canton Coin and a Canton Coin banner (ASCII art) for the console
object CoinApp extends CantonAppDriver[CoinEnvironmentImpl] {

  override def loadConfig(config: Config): Either[CantonConfigError, CoinConfig] =
    CoinConfig.load(config)

  override protected def environmentFactory: EnvironmentFactory[CoinEnvironmentImpl] =
    CoinEnvironmentFactory

  override protected def withManualStart(config: CoinConfig): CoinConfig =
    config.copy(parameters = config.parameters.copy(manualStart = true))
}
