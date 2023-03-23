package com.daml.network

import com.daml.network.config.CNNodeConfig
import com.daml.network.environment.{CNNodeEnvironmentFactory, CNNodeEnvironmentImpl}
import com.digitalasset.canton.CantonAppDriver
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.environment.EnvironmentFactory
import com.typesafe.config.Config

// TODO(#736): generalize. e.g. custom Cli class for Canton Network Node for the console
object CNNodeApp extends CantonAppDriver[CNNodeEnvironmentImpl] {

  override def loadConfig(config: Config): Either[CantonConfigError, CNNodeConfig] =
    CNNodeConfig.load(config)

  override protected def environmentFactory: EnvironmentFactory[CNNodeEnvironmentImpl] =
    CNNodeEnvironmentFactory

  override protected def withManualStart(config: CNNodeConfig): CNNodeConfig =
    config.copy(parameters = config.parameters.copy(manualStart = true))
}
