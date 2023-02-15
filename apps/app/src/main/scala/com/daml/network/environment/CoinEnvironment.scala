package com.daml.network.environment

import cats.syntax.either.*
import com.daml.network.config.CNNodeConfig
import com.daml.network.directory.DirectoryAppBootstrap
import com.daml.network.directory.config.LocalDirectoryAppConfig
import com.daml.network.metrics.CoinMetricsFactory
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
import com.daml.network.wallet.WalletAppBootstrap
import com.daml.network.wallet.config.WalletAppBackendConfig
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

trait CoinEnvironment extends Environment {

  override type Config = CNNodeConfig
  override type Console = CoinConsoleEnvironment

  // TODO(tech-debt): check that the CoinMetrics factory is used in all of this trait's methods.
  val coinMetrics = CoinMetricsFactory.forConfig(config.monitoring.metrics)

  protected def createValidator(
      name: String,
      validatorConfig: ValidatorAppBackendConfig,
  ): ValidatorAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(ValidatorAppBootstrap.LoggerFactoryKeyName, name)
    ValidatorAppBootstrap(
      name,
      validatorConfig,
      config.tryValidatorAppParametersByString(name),
      createClock(appLoggerFactory),
      coinMetrics.forValidator(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      writeHealthDumpToFile,
      CoinRetries(appLoggerFactory),
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
      createClock(appLoggerFactory),
      testingTimeService,
      coinMetrics.forSvc(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      writeHealthDumpToFile,
      CoinRetries(appLoggerFactory),
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
      createClock(appLoggerFactory),
      coinMetrics.forSv(name),
      testingConfig,
      appLoggerFactory,
      writeHealthDumpToFile,
      CoinRetries(appLoggerFactory),
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
      createClock(appLoggerFactory),
      testingTimeService,
      coinMetrics.forScan(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      writeHealthDumpToFile,
      CoinRetries(appLoggerFactory),
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

  protected def createWallet(
      name: String,
      walletConfig: WalletAppBackendConfig,
  ): WalletAppBootstrap = {
    val appLoggerFactory = loggerFactory.append(WalletAppBootstrap.LoggerFactoryKeyName, name)
    WalletAppBootstrap(
      name,
      walletConfig,
      config.tryWalletAppBackendParametersByString(name),
      createClock(appLoggerFactory),
      testingTimeService,
      coinMetrics.forWallet(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      writeHealthDumpToFile,
      CoinRetries(appLoggerFactory),
      configuredOpenTelemetry,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )
  }

  lazy val wallets = new WalletApps(
    createWallet,
    migrationsFactory,
    timeouts,
    config.walletBackendsByString,
    config.tryWalletAppBackendParametersByString,
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
      createClock(appLoggerFactory),
      testingTimeService,
      coinMetrics.forDirectory(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      writeHealthDumpToFile,
      CoinRetries(appLoggerFactory),
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
      createClock(appLoggerFactory),
      testingTimeService,
      coinMetrics.forSplitwell(name),
      testingConfig,
      futureSupervisor,
      appLoggerFactory,
      writeHealthDumpToFile,
      CoinRetries(appLoggerFactory),
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
      // Ordering here matches CoinConsoleEnvironment.startupOrderPrecedence
      svcs.startAll.left.getOrElse(Seq.empty) ++
        svs.startAll.left.getOrElse(Seq.empty) ++
        scans.startAll.left.getOrElse(Seq.empty) ++
        validators.startAll.left.getOrElse(Seq.empty) ++
        wallets.startAll.left.getOrElse(Seq.empty) ++
        directories.startAll.left.getOrElse(Seq.empty) ++
        splitwells.startAll.left.getOrElse(Seq.empty)
    Either.cond(errors.isEmpty, (), errors)
  }

  // Ordering here matches CoinConsoleEnvironment.startupOrderPrecedence
  def allCoinNodes: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    List(svcs, svs, scans, validators, wallets, directories, splitwells)

  override def allNodes: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    super.allNodes ::: allCoinNodes

}

object CoinEnvironmentFactory extends EnvironmentFactory[CoinEnvironmentImpl] {
  override def create(
      config: CNNodeConfig,
      loggerFactory: NamedLoggerFactory,
      testingConfigInternal: TestingConfigInternal,
  ): CoinEnvironmentImpl = {
    val envLoggerFactory = config.name.fold(loggerFactory)(loggerFactory.append("config", _))
    new CoinEnvironmentImpl(config, testingConfigInternal, envLoggerFactory)
  }
}

class CoinEnvironmentImpl(
    override val config: CNNodeConfig,
    override val testingConfig: TestingConfigInternal,
    override val loggerFactory: NamedLoggerFactory,
) extends CoinEnvironment {
  override type Config = CNNodeConfig

  // dump config (without sensitive data) to ease debugging
  logger.info(s"CoinEnvironment with config = {\n${config.dumpString}\n}")

  override def _createConsole(
      consoleOutput: ConsoleOutput,
      createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner,
  ): CoinConsoleEnvironment =
    new CoinConsoleEnvironment(this, consoleOutput, createAdminCommandRunner)

  override protected def createHealthDumpGenerator(
      commandRunner: GrpcAdminCommandRunner
  ): HealthDumpGenerator[_] = {
    new CoinHealthDumpGenerator(this, commandRunner)
  }

  override protected val participantNodeFactory
      : ParticipantNodeBootstrap.Factory[Config#ParticipantConfigType] =
    ParticipantNodeBootstrap.CommunityParticipantFactory

  override protected val domainFactory: DomainNodeBootstrap.Factory[Config#DomainConfigType] =
    DomainNodeBootstrap.CommunityDomainFactory
  override type Console = CoinConsoleEnvironment

  override protected lazy val migrationsFactory: DbMigrationsFactory =
    new CommunityDbMigrationsFactory(loggerFactory)

  override def isEnterprise: Boolean = false
}
