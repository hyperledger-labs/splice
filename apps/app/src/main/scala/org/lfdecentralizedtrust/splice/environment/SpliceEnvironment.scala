// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.syntax.either.*
import com.digitalasset.canton.config.{CommunityCantonEdition, TestingConfigInternal}
import com.digitalasset.canton.console.ConsoleOutput
import com.digitalasset.canton.environment.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNodeBootstrapFactoryImpl
import com.digitalasset.canton.synchronizer.mediator.MediatorNodeBootstrapFactoryImpl
import com.digitalasset.canton.synchronizer.sequencer.SequencerNodeBootstrapFactoryImpl
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.metrics.SpliceMetricsFactory
import org.lfdecentralizedtrust.splice.scan.ScanAppBootstrap
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.splitwell.SplitwellAppBootstrap
import org.lfdecentralizedtrust.splice.splitwell.config.SplitwellAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.SvAppBootstrap
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.validator.ValidatorAppBootstrap
import org.lfdecentralizedtrust.splice.validator.config.ValidatorAppBackendConfig

class SpliceEnvironment(
    config: SpliceConfig,
    testingConfig: TestingConfigInternal,
    loggerFactory: NamedLoggerFactory,
) extends Environment[SpliceConfig](
      config,
      CommunityCantonEdition,
      testingConfig,
      ParticipantNodeBootstrapFactoryImpl,
      SequencerNodeBootstrapFactoryImpl,
      MediatorNodeBootstrapFactoryImpl,
      loggerFactory,
    ) {

  // dump config (without sensitive data) to ease debugging
  logger.info(s"SpliceEnvironment with config = {\n${config.dumpString}\n}")

  private lazy val metrics = SpliceMetricsFactory(
    metricsRegistry,
    dbStorageHistograms,
    loggerFactory,
    config.parameters.timeouts.processing,
  )

  protected def createValidator(
      name: String,
      validatorConfig: ValidatorAppBackendConfig,
  ): ValidatorAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(ValidatorAppBootstrap.LoggerFactoryKeyName, name)
    ValidatorAppBootstrap(
      name,
      validatorConfig,
      config.tryValidatorAppParametersByString(name),
      createClock(Some(ValidatorAppBootstrap.LoggerFactoryKeyName -> name)),
      metrics.forValidator(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      configuredOpenTelemetry,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )
  }

  lazy val validators = new ValidatorApps(
    createValidator,
    timeouts,
    config.validatorsByString,
    config.tryValidatorAppParametersByString,
    loggerFactory,
  )

  protected def createSv(
      name: String,
      svConfig: SvAppBackendConfig,
  ): SvAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(SvAppBootstrap.LoggerFactoryKeyName, name)
    SvAppBootstrap(
      name,
      svConfig,
      config.trySvAppParametersByString(name),
      createClock(Some(SvAppBootstrap.LoggerFactoryKeyName -> name)),
      metrics.forSv(name),
      testingConfig,
      appLoggerFactory,
      futureSupervisor,
      configuredOpenTelemetry,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )
  }

  lazy val svs = new SvApps(
    createSv,
    timeouts,
    config.svsByString,
    config.trySvAppParametersByString,
    loggerFactory,
  )

  protected def createScan(
      name: String,
      scanConfig: ScanAppBackendConfig,
  ): ScanAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(ScanAppBootstrap.LoggerFactoryKeyName, name)
    ScanAppBootstrap(
      name,
      scanConfig,
      config.tryScanAppParametersByString(name),
      createClock(Some(ScanAppBootstrap.LoggerFactoryKeyName -> name)),
      metrics.forScan(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      configuredOpenTelemetry,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )
  }

  lazy val scans = new ScanApps(
    createScan,
    timeouts,
    config.scansByString,
    config.tryScanAppParametersByString,
    loggerFactory,
  )

  protected def createSplitwell(
      name: String,
      splitwellConfig: SplitwellAppBackendConfig,
  ): SplitwellAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(SplitwellAppBootstrap.LoggerFactoryKeyName, name)
    SplitwellAppBootstrap(
      name,
      splitwellConfig,
      config.trySplitwellAppParametersByString(name),
      createClock(Some(SplitwellAppBootstrap.LoggerFactoryKeyName -> name)),
      metrics.forSplitwell(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      configuredOpenTelemetry,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )
  }

  lazy val splitwells = new SplitwellApps(
    createSplitwell,
    timeouts,
    config.splitwellsByString,
    config.trySplitwellAppParametersByString,
    loggerFactory,
  )

  // Ordering here matches SpliceConsoleEnvironment.startupOrderPrecedence
  def allSplices: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    List(svs, scans, validators, splitwells)

  override def allNodes: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    super.allNodes ::: allSplices

  override type Console = SpliceConsoleEnvironment

  override def _createConsole(
      consoleOutput: ConsoleOutput
  ): SpliceConsoleEnvironment =
    new SpliceConsoleEnvironment(this, consoleOutput)
}

object SpliceEnvironmentFactory extends EnvironmentFactory[SpliceConfig, SpliceEnvironment] {
  override def create(
      config: SpliceConfig,
      loggerFactory: NamedLoggerFactory,
      testingConfigInternal: TestingConfigInternal,
  ): SpliceEnvironment = {
    val envLoggerFactory = config.name.fold(loggerFactory)(loggerFactory.append("config", _))
    new SpliceEnvironment(config, testingConfigInternal, envLoggerFactory)
  }
}
