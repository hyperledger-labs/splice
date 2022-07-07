package com.daml.network.environment

import cats.syntax.either._
import com.daml.network.config.CoinConfig
import com.daml.network.metrics.CoinMetricsFactory
import com.daml.network.svc.SvcAppBootstrap
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.validator.ValidatorNodeBootstrap
import com.daml.network.validator.config.LocalValidatorConfig
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
      validatorConfig: LocalValidatorConfig,
  ): ValidatorNodeBootstrap =
    ValidatorNodeBootstrap.ValidatorFactory
      .create(
        name,
        validatorConfig,
        config.tryValidatorNodeParametersByString(name),
        createClock(Some(ValidatorNodeBootstrap.LoggerFactoryKeyName -> name)),
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
    config.tryValidatorNodeParametersByString,
    loggerFactory,
  )

  protected def createSvc(
      name: String,
      svcConfig: LocalSvcAppConfig,
  ): SvcAppBootstrap =
    SvcAppBootstrap.SvcAppFactory
      .create(
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

  /** Start all instances described in the configuration
    */
  override def startAll(): Either[Seq[StartupError], Unit] = {
    val errors =
      validators.startAll.left.getOrElse(Seq.empty) ++
        svcs.startAll.left.getOrElse(Seq.empty)
    Either.cond(errors.isEmpty, (), errors)
  }

  override def allNodes = super.allNodes :+ validators :+ svcs

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
