// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.syntax.either.*
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
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.console.{ConsoleOutput, GrpcAdminCommandRunner, HealthDumpGenerator}
import com.digitalasset.canton.domain.mediator.MediatorNodeBootstrap
import com.digitalasset.canton.domain.sequencing.SequencerNodeBootstrap
import com.digitalasset.canton.environment.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNodeBootstrap
import com.digitalasset.canton.resource.{CommunityDbMigrationsFactory, DbMigrationsFactory}

trait SpliceEnvironment extends Environment {

  override type Config = SpliceConfig
  override type Console = SpliceConsoleEnvironment

  private lazy val metrics = SpliceMetricsFactory(
    metricsRegistry,
    dbStorageHistograms,
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
    migrationsFactory,
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
    migrationsFactory,
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
    migrationsFactory,
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
    migrationsFactory,
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

}

object SpliceEnvironmentFactory extends EnvironmentFactory[EnvironmentImpl] {
  override def create(
      config: SpliceConfig,
      loggerFactory: NamedLoggerFactory,
      testingConfigInternal: TestingConfigInternal,
  ): EnvironmentImpl = {
    val envLoggerFactory = config.name.fold(loggerFactory)(loggerFactory.append("config", _))
    new EnvironmentImpl(config, testingConfigInternal, envLoggerFactory)
  }
}

class EnvironmentImpl(
    override val config: SpliceConfig,
    override val testingConfig: TestingConfigInternal,
    override val loggerFactory: NamedLoggerFactory,
) extends SpliceEnvironment {
  override type Config = SpliceConfig

  // dump config (without sensitive data) to ease debugging
  logger.info(s"SpliceEnvironment with config = {\n${config.dumpString}\n}")

  override def _createConsole(
      consoleOutput: ConsoleOutput
  ): SpliceConsoleEnvironment =
    new SpliceConsoleEnvironment(this, consoleOutput)

  override protected def createHealthDumpGenerator(
      commandRunner: GrpcAdminCommandRunner
  ): HealthDumpGenerator[_] = {
    new SpliceHealthDumpGenerator(this, commandRunner)
  }

  override protected def participantNodeFactory
      : ParticipantNodeBootstrap.Factory[Config#ParticipantConfigType, ParticipantNodeBootstrap] =
    ParticipantNodeBootstrap.CommunityParticipantFactory

  override protected lazy val migrationsFactory: DbMigrationsFactory =
    new CommunityDbMigrationsFactory(loggerFactory)

  // createWhateverX copied from canton's CommunityEnvironment:
  override protected def createMediator(
      name: String,
      mediatorConfig: Config#MediatorNodeConfigType,
  ): MediatorNodeBootstrap = ???

  override protected def createSequencer(
      name: String,
      sequencerConfig: Config#SequencerNodeConfigType,
  ): SequencerNodeBootstrap = ???

  override def isEnterprise: Boolean = false
}
