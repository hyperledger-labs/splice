// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice

import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.environment.{
  BuildInfo,
  SpliceEnvironment,
  SpliceEnvironmentFactory,
}
import com.digitalasset.canton.CantonAppDriver
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.config.DefaultPorts
import com.digitalasset.canton.environment.EnvironmentFactory

// TODO(DACH-NY/canton-network-node#736): generalize. e.g. custom Cli class for Splice Node for the console
object SpliceApp extends CantonAppDriver {

  override type Config = SpliceConfig
  override type E = SpliceEnvironment

  override protected def printVersion(): Unit = {
    Console.out.println(s"Splice: ${BuildInfo.compiledVersion}")
    super.printVersion()
  }

  override def loadConfig(
      config: com.typesafe.config.Config,
      defaultPorts: Option[DefaultPorts],
  ): Either[CantonConfigError, SpliceConfig] =
    SpliceConfig.load(config)

  override protected def environmentFactory: EnvironmentFactory[SpliceConfig, SpliceEnvironment] =
    SpliceEnvironmentFactory

  override protected def withManualStart(config: SpliceConfig): SpliceConfig =
    config.copy()
  // config.copy(parameters = config.parameters.copy(manualStart = true))

  override protected def logAppVersion(): Unit =
    logger.info(s"Starting Splice version ${BuildInfo.compiledVersion}")
}
