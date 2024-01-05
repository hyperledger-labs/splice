package com.daml.network.environment

import cats.syntax.either.*
import com.daml.network.config.CNNodeConfig
import com.daml.network.metrics.CNNodeMetricsFactory
import com.daml.network.scan.ScanAppBootstrap
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.splitwell.SplitwellAppBootstrap
import com.daml.network.splitwell.config.SplitwellAppBackendConfig
import com.daml.network.sv.SvAppBootstrap
import com.daml.network.sv.config.SvAppBackendConfig
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
import com.digitalasset.canton.crypto.CommunityCryptoFactory
import com.digitalasset.canton.crypto.admin.grpc.GrpcVaultService.CommunityGrpcVaultServiceFactory
import com.digitalasset.canton.crypto.store.CryptoPrivateStore.CommunityCryptoPrivateStoreFactory
import com.digitalasset.canton.domain.DomainNodeBootstrap
import com.digitalasset.canton.domain.admin.v0.EnterpriseSequencerAdministrationServiceGrpc
import com.digitalasset.canton.domain.mediator.{
  CommunityMediatorNodeXConfig,
  CommunityMediatorReplicaManager,
  CommunityMediatorRuntimeFactory,
  MediatorNodeBootstrapX,
  MediatorNodeConfigCommon,
  MediatorNodeParameters,
}
import com.digitalasset.canton.domain.metrics.MediatorNodeMetrics
import com.digitalasset.canton.domain.sequencing.SequencerNodeBootstrapX
import com.digitalasset.canton.domain.sequencing.config.CommunitySequencerNodeXConfig
import com.digitalasset.canton.domain.sequencing.sequencer.CommunitySequencerFactory
import com.digitalasset.canton.environment.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.networking.grpc.StaticGrpcServices
import com.digitalasset.canton.participant.{ParticipantNodeBootstrap, ParticipantNodeBootstrapX}
import com.digitalasset.canton.resource.{
  CommunityDbMigrationsFactory,
  CommunityStorageFactory,
  DbMigrationsFactory,
}

trait CNNodeEnvironment extends Environment {

  override type Config = CNNodeConfig
  override type Console = CNNodeConsoleEnvironment

  val cnNodeMetrics =
    CNNodeMetricsFactory.forConfig(
      configuredOpenTelemetry.openTelemetry.getMeterProvider,
      testingConfig.metricsFactoryType,
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

  // Ordering here matches CNNodeConsoleEnvironment.startupOrderPrecedence
  def allCNNodes: List[Nodes[CantonNode, CantonNodeBootstrap[CantonNode]]] =
    List(svs, scans, validators, splitwells)

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
      : ParticipantNodeBootstrap.Factory[Config#ParticipantConfigType, ParticipantNodeBootstrap] =
    ParticipantNodeBootstrap.CommunityParticipantFactory

  override protected def participantNodeFactoryX
      : ParticipantNodeBootstrap.Factory[Config#ParticipantConfigType, ParticipantNodeBootstrapX] =
    ParticipantNodeBootstrapX.CommunityParticipantFactory

  override protected val domainFactory: DomainNodeBootstrap.Factory[Config#DomainConfigType] =
    DomainNodeBootstrap.CommunityDomainFactory
  override type Console = CNNodeConsoleEnvironment

  override protected lazy val migrationsFactory: DbMigrationsFactory =
    new CommunityDbMigrationsFactory(loggerFactory)

  // createWhateverX copied from canton's CommunityEnvironment:
  override protected def createMediatorX(
      name: String,
      mediatorConfig: CommunityMediatorNodeXConfig,
  ): MediatorNodeBootstrapX = {
    val factoryArguments = mediatorNodeFactoryArguments(name, mediatorConfig)
    val arguments = factoryArguments
      .toCantonNodeBootstrapCommonArguments(
        new CommunityStorageFactory(mediatorConfig.storage),
        new CommunityCryptoFactory(),
        new CommunityCryptoPrivateStoreFactory(),
        new CommunityGrpcVaultServiceFactory(),
      )
      .valueOr(err =>
        throw new RuntimeException(s"Failed to create mediator bootstrap: $err")
      ): CantonNodeBootstrapCommonArguments[
      MediatorNodeConfigCommon,
      MediatorNodeParameters,
      MediatorNodeMetrics,
    ]

    new MediatorNodeBootstrapX(
      arguments,
      new CommunityMediatorReplicaManager(
        config.parameters.timeouts.processing,
        loggerFactory,
      ),
      CommunityMediatorRuntimeFactory,
    )
  }

  override protected def createSequencerX(
      name: String,
      sequencerConfig: CommunitySequencerNodeXConfig,
  ): SequencerNodeBootstrapX = {
    val nodeFactoryArguments = NodeFactoryArguments(
      name,
      sequencerConfig,
      config.sequencerNodeParametersByStringX(name),
      createClock(Some(SequencerNodeBootstrapX.LoggerFactoryKeyName -> name)),
      metricsFactory.forSequencer(name),
      testingConfig,
      futureSupervisor,
      loggerFactory.append(SequencerNodeBootstrapX.LoggerFactoryKeyName, name),
      writeHealthDumpToFile,
      configuredOpenTelemetry,
    )

    val boostrapCommonArguments = nodeFactoryArguments
      .toCantonNodeBootstrapCommonArguments(
        new CommunityStorageFactory(sequencerConfig.storage),
        new CommunityCryptoFactory(),
        new CommunityCryptoPrivateStoreFactory(),
        new CommunityGrpcVaultServiceFactory,
      )
      .valueOr(err => throw new RuntimeException(s"Failed to create sequencer-x node $name: $err"))

    new SequencerNodeBootstrapX(
      boostrapCommonArguments,
      CommunitySequencerFactory,
      (_, _) =>
        Some(
          StaticGrpcServices
            .notSupportedByCommunity(EnterpriseSequencerAdministrationServiceGrpc.SERVICE, logger)
        ),
    )
  }

  override def isEnterprise: Boolean = false
}
