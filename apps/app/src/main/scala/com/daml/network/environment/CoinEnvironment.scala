package com.daml.network.environment

import cats.syntax.either._
import com.daml.network.config.CoinConfig
import com.daml.network.directory.provider.DirectoryProviderAppBootstrap
import com.daml.network.directory.provider.config.LocalDirectoryProviderAppConfig
import com.daml.network.directory.user.DirectoryUserAppBootstrap
import com.daml.network.directory.user.config.LocalDirectoryUserAppConfig
import com.daml.network.metrics.CoinMetricsFactory
import com.daml.network.scan.ScanAppBootstrap
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.splitwise.SplitwiseAppBootstrap
import com.daml.network.splitwise.config.LocalSplitwiseAppConfig
import com.daml.network.svc.SvcAppBootstrap
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.validator.ValidatorAppBootstrap
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.network.wallet.WalletAppBootstrap
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.console.{
  ConsoleEnvironment,
  ConsoleGrpcAdminCommandRunner,
  ConsoleOutput,
}
import com.digitalasset.canton.domain.DomainNodeBootstrap
import com.digitalasset.canton.environment._
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNodeBootstrap
import com.digitalasset.canton.resource.{CommunityDbMigrationsFactory, DbMigrationsFactory}

trait CoinEnvironment extends Environment {

  override type Config = CoinConfig
  override type Console = CoinConsoleEnvironment

  // TODO(Arne): check that this is used in all of this trait's methods.
  val coinMetrics = CoinMetricsFactory.forConfig(config.monitoring.metrics)

  protected def createValidator(
      name: String,
      validatorConfig: LocalValidatorAppConfig,
  ): ValidatorAppBootstrap =
    ValidatorAppBootstrap(
      name,
      validatorConfig,
      config.tryValidatorAppParametersByString(name),
      createClock(Some(ValidatorAppBootstrap.LoggerFactoryKeyName -> name)),
      testingTimeService,
      coinMetrics.forValidator(name),
      testingConfig,
      futureSupervisor,
      loggerFactory,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )

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
      svcConfig: LocalSvcAppConfig,
  ): SvcAppBootstrap =
    SvcAppBootstrap(
      name,
      svcConfig,
      config.trySvcAppParametersByString(name),
      createClock(Some(SvcAppBootstrap.LoggerFactoryKeyName -> name)),
      testingTimeService,
      coinMetrics.forSvc(name),
      testingConfig,
      futureSupervisor,
      loggerFactory,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )

  lazy val svcs = new SvcApps(
    createSvc,
    migrationsFactory,
    timeouts,
    config.svcsByString,
    config.trySvcAppParametersByString,
    loggerFactory,
  )

  protected def createScan(
      name: String,
      scanConfig: LocalScanAppConfig,
  ): ScanAppBootstrap =
    ScanAppBootstrap(
      name,
      scanConfig,
      config.tryScanAppParametersByString(name),
      createClock(Some(ScanAppBootstrap.LoggerFactoryKeyName -> name)),
      testingTimeService,
      coinMetrics.forScan(name),
      testingConfig,
      futureSupervisor,
      loggerFactory,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )

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
      walletConfig: LocalWalletAppConfig,
  ): WalletAppBootstrap =
    WalletAppBootstrap(
      name,
      walletConfig,
      config.tryWalletAppParametersByString(name),
      createClock(Some(WalletAppBootstrap.LoggerFactoryKeyName -> name)),
      testingTimeService,
      coinMetrics.forWallet(name),
      testingConfig,
      futureSupervisor,
      loggerFactory,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )

  lazy val wallets = new WalletApps(
    createWallet,
    migrationsFactory,
    timeouts,
    config.walletsByString,
    config.tryWalletAppParametersByString,
    loggerFactory,
  )

  protected def createDirectoryProvider(
      name: String,
      directoryProviderConfig: LocalDirectoryProviderAppConfig,
  ): DirectoryProviderAppBootstrap =
    DirectoryProviderAppBootstrap(
      name,
      directoryProviderConfig,
      config.tryDirectoryProviderAppParametersByString(name),
      createClock(Some(DirectoryProviderAppBootstrap.LoggerFactoryKeyName -> name)),
      testingTimeService,
      coinMetrics.forDirectoryProvider(name),
      testingConfig,
      futureSupervisor,
      loggerFactory,
    ).valueOr(err =>
      throw new RuntimeException(
        s"Failed to create participant bootstrap: $err"
      )
    )

  lazy val directoryProviders = new DirectoryProviderApps(
    createDirectoryProvider,
    migrationsFactory,
    timeouts,
    config.directoryProvidersByString,
    config.tryDirectoryProviderAppParametersByString,
    loggerFactory,
  )

  protected def createDirectoryUser(
      name: String,
      directoryUserConfig: LocalDirectoryUserAppConfig,
  ): DirectoryUserAppBootstrap =
    DirectoryUserAppBootstrap(
      name,
      directoryUserConfig,
      config.tryDirectoryUserAppParametersByString(name),
      createClock(Some(DirectoryUserAppBootstrap.LoggerFactoryKeyName -> name)),
      testingTimeService,
      coinMetrics.forDirectoryUser(name),
      testingConfig,
      futureSupervisor,
      loggerFactory,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )

  lazy val directoryUsers = new DirectoryUserApps(
    createDirectoryUser,
    migrationsFactory,
    timeouts,
    config.directoryUsersByString,
    config.tryDirectoryUserAppParametersByString,
    loggerFactory,
  )

  protected def createSplitwise(
      name: String,
      splitwiseConfig: LocalSplitwiseAppConfig,
  ): SplitwiseAppBootstrap =
    SplitwiseAppBootstrap(
      name,
      splitwiseConfig,
      config.trySplitwiseAppParametersByString(name),
      createClock(Some(SplitwiseAppBootstrap.LoggerFactoryKeyName -> name)),
      testingTimeService,
      coinMetrics.forSplitwise(name),
      testingConfig,
      futureSupervisor,
      loggerFactory,
    )
      .valueOr(err =>
        throw new RuntimeException(
          s"Failed to create participant bootstrap: $err"
        )
      )

  lazy val splitwises = new SplitwiseApps(
    createSplitwise,
    migrationsFactory,
    timeouts,
    config.splitwisesByString,
    config.trySplitwiseAppParametersByString,
    loggerFactory,
  )

  /** Start all instances described in the configuration
    */
  override def startAll(): Either[Seq[StartupError], Unit] = {
    val errors =
      validators.startAll.left.getOrElse(Seq.empty) ++
        scans.startAll.left.getOrElse(Seq.empty) ++
        svcs.startAll.left.getOrElse(Seq.empty) ++
        wallets.startAll.left.getOrElse(Seq.empty) ++
        directoryProviders.startAll.left.getOrElse(Seq.empty) ++
        directoryUsers.startAll.left.getOrElse(Seq.empty) ++
        splitwises.startAll.left.getOrElse(Seq.empty)
    Either.cond(errors.isEmpty, (), errors)
  }

  def allCoinNodes: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    List(validators, svcs, scans, wallets, directoryProviders, directoryUsers, splitwises)

  override def allNodes: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    super.allNodes ::: allCoinNodes

}

object CoinEnvironmentFactory extends EnvironmentFactory[CoinEnvironmentImpl] {
  override def create(
      config: CoinConfig,
      loggerFactory: NamedLoggerFactory,
      testingConfigInternal: TestingConfigInternal,
  ): CoinEnvironmentImpl =
    new CoinEnvironmentImpl(config, testingConfigInternal, loggerFactory)
}

class CoinEnvironmentImpl(
    override val config: CoinConfig,
    override val testingConfig: TestingConfigInternal,
    override val loggerFactory: NamedLoggerFactory,
) extends CoinEnvironment {
  override type Config = CoinConfig

  override def createConsole(
      consoleOutput: ConsoleOutput,
      createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner,
  ): CoinConsoleEnvironment =
    new CoinConsoleEnvironment(this, consoleOutput, createAdminCommandRunner)

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
