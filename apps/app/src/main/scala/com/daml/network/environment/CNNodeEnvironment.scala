package com.daml.network.environment

import cats.syntax.either.*
import com.daml.network.config.CNNodeConfig
import com.daml.network.directory.DirectoryAppBootstrap
import com.daml.network.directory.config.LocalDirectoryAppConfig
import com.daml.network.metrics.CNNodeMetricsFactory
import com.daml.network.scan.ScanAppBootstrap
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.splitwell.SplitwellAppBootstrap
import com.daml.network.splitwell.config.SplitwellAppBackendConfig
import com.daml.network.sv.SvAppBootstrap
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.svc.SvcAppBootstrap
import com.daml.network.svc.config.SvcAppBackendConfig
import com.daml.network.validator.ValidatorAppBootstrap
import com.daml.network.validator.config.ValidatorAppBackendConfig
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.console.{
  ConsoleEnvironment,
  ConsoleGrpcAdminCommandRunner,
  ConsoleOutput,
  GrpcAdminCommandRunner,
  HealthDumpGenerator,
}
import com.digitalasset.canton.domain.DomainNodeBootstrap
import com.digitalasset.canton.environment.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNodeBootstrap
import com.digitalasset.canton.resource.{CommunityDbMigrationsFactory, DbMigrationsFactory}

trait CNNodeEnvironment extends Environment {

  override type Config = CNNodeConfig
  override type Console = CNNodeConsoleEnvironment

  // TODO(tech-debt): check that the CNNodeMetrics factory is used in all of this trait's methods.
  val cnNodeMetrics =
    CNNodeMetricsFactory.forConfig(config.monitoring.metrics, testingConfig.metricsFactoryType)

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
      cnNodeMetrics.forValidator(name),
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

  protected def createSvc(
      name: String,
      svcConfig: SvcAppBackendConfig,
  ): SvcAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(SvcAppBootstrap.LoggerFactoryKeyName, name)
    SvcAppBootstrap(
      name,
      svcConfig,
      config.trySvcAppParametersByString(name),
      createClock(Some(SvcAppBootstrap.LoggerFactoryKeyName -> name)),
      cnNodeMetrics.forSvc(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      writeHealthDumpToFile,
      configuredOpenTelemetry,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )
  }

  lazy val svcs = new SvcApps(
    createSvc,
    migrationsFactory,
    timeouts,
    config.svcsByString,
    config.trySvcAppParametersByString,
    loggerFactory,
  )

  protected def createSv(
      name: String,
      svConfig: LocalSvAppConfig,
  ): SvAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(SvAppBootstrap.LoggerFactoryKeyName, name)
    SvAppBootstrap(
      name,
      svConfig,
      config.trySvAppParametersByString(name),
      createClock(Some(SvAppBootstrap.LoggerFactoryKeyName -> name)),
      cnNodeMetrics.forSv(name),
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
      cnNodeMetrics.forScan(name),
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

  protected def createDirectory(
      name: String,
      directoryConfig: LocalDirectoryAppConfig,
  ): DirectoryAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(DirectoryAppBootstrap.LoggerFactoryKeyName, name)
    DirectoryAppBootstrap(
      name,
      directoryConfig,
      config.tryDirectoryAppParametersByString(name),
      createClock(Some(DirectoryAppBootstrap.LoggerFactoryKeyName -> name)),
      cnNodeMetrics.forDirectory(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      configuredOpenTelemetry,
    ).valueOr(err =>
      throw new RuntimeException(
        s"Failed to create participant bootstrap: $err"
      )
    )
  }

  lazy val directories = new DirectoryApps(
    createDirectory,
    migrationsFactory,
    timeouts,
    config.directoriesByString,
    config.tryDirectoryAppParametersByString,
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
      cnNodeMetrics.forSplitwell(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      writeHealthDumpToFile,
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

  /** Start all instances described in the configuration
    */
  override def startAll(): Either[Seq[StartupError], Unit] = {
    val errors =
      // Ordering here matches CNNodeConsoleEnvironment.startupOrderPrecedence
      svcs.startAll.left.getOrElse(Seq.empty) ++
        svs.startAll.left.getOrElse(Seq.empty) ++
        scans.startAll.left.getOrElse(Seq.empty) ++
        validators.startAll.left.getOrElse(Seq.empty) ++
        directories.startAll.left.getOrElse(Seq.empty) ++
        splitwells.startAll.left.getOrElse(Seq.empty)
    Either.cond(errors.isEmpty, (), errors)
  }

  // Ordering here matches CNNodeConsoleEnvironment.startupOrderPrecedence
  def allCNNodes: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    List(svcs, svs, scans, validators, directories, splitwells)

  override def allNodes: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    super.allNodes ::: allCNNodes

}

object CNNodeEnvironmentFactory extends EnvironmentFactory[CNNodeEnvironmentImpl] {
  override def create(
      config: CNNodeConfig,
      loggerFactory: NamedLoggerFactory,
      testingConfigInternal: TestingConfigInternal,
  ): CNNodeEnvironmentImpl = {
    val envLoggerFactory = config.name.fold(loggerFactory)(loggerFactory.append("config", _))
    new CNNodeEnvironmentImpl(config, testingConfigInternal, envLoggerFactory)
  }
}

class CNNodeEnvironmentImpl(
    override val config: CNNodeConfig,
    override val testingConfig: TestingConfigInternal,
    override val loggerFactory: NamedLoggerFactory,
) extends CNNodeEnvironment {
  override type Config = CNNodeConfig

  // dump config (without sensitive data) to ease debugging
  logger.info(s"CNNodeEnvironment with config = {\n${config.dumpString}\n}")

  override def _createConsole(
      consoleOutput: ConsoleOutput,
      createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner,
  ): CNNodeConsoleEnvironment =
    new CNNodeConsoleEnvironment(this, consoleOutput, createAdminCommandRunner)

  override protected def createHealthDumpGenerator(
      commandRunner: GrpcAdminCommandRunner
  ): HealthDumpGenerator[_] = {
    new CNNodeHealthDumpGenerator(this, commandRunner)
  }

  override protected val participantNodeFactory
      : ParticipantNodeBootstrap.Factory[Config#ParticipantConfigType] =
    ParticipantNodeBootstrap.CommunityParticipantFactory

  override protected val domainFactory: DomainNodeBootstrap.Factory[Config#DomainConfigType] =
    DomainNodeBootstrap.CommunityDomainFactory
  override type Console = CNNodeConsoleEnvironment

  override protected lazy val migrationsFactory: DbMigrationsFactory =
    new CommunityDbMigrationsFactory(loggerFactory)

  override def isEnterprise: Boolean = false
}
