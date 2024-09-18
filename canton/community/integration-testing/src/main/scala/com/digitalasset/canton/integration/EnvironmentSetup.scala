// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.integration

import com.daml.metrics.Timed
import com.digitalasset.canton.CloseableTest
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.{
  CommandService,
  CommandSubmissionService,
}
import com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.config.DefaultPorts
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.environment.Environment
import com.digitalasset.canton.integration.EnvironmentSetup.EnvironmentSetupException
import com.digitalasset.canton.logging.{LogEntry, NamedLogging, SuppressingLogger}
import com.digitalasset.canton.metrics.{MetricsFactoryType, ScopedInMemoryMetricsFactory}
import com.digitalasset.canton.networking.grpc.{CantonGrpcUtil, GrpcError}
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.{Assertion, BeforeAndAfterAll, Suite}

import java.util.concurrent.TimeUnit
import scala.util.Try
import scala.util.control.{NoStackTrace, NonFatal}

/** Provides an ability to create a canton environment when needed for test.
  * Include [[IsolatedEnvironments]] or [[SharedEnvironment]] to determine when this happens.
  * Uses [[ConcurrentEnvironmentLimiter]] to ensure we limit the number of concurrent environments in a test run.
  */
sealed trait EnvironmentSetup[E <: Environment, TCE <: TestConsoleEnvironment[E]]
    extends BeforeAndAfterAll {
  this: Suite with HasEnvironmentDefinition[E, TCE] with IntegrationTestMetrics with NamedLogging =>

  private lazy val envDef = environmentDefinition

  // plugins are registered during construction from a single thread
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var plugins: Seq[EnvironmentSetupPlugin[E, TCE]] = Seq()

  protected[integration] def registerPlugin(plugin: EnvironmentSetupPlugin[E, TCE]): Unit =
    plugins = plugins :+ plugin

  override protected def beforeAll(): Unit = {
    Timed.value(testInfrastructureSuiteMetrics.pluginsBeforeTests, plugins.foreach(_.beforeTests()))
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    try super.afterAll()
    finally
      Timed.value(testInfrastructureSuiteMetrics.pluginsAfterTests, plugins.foreach(_.afterTests()))
  }

  /** Provide an environment for an individual test either by reusing an existing one or creating a new one
    * depending on the approach being used.
    */
  def provideEnvironment(testName: String): TCE

  /** Optional hook for implementors to know when a test has finished and be provided the environment instance.
    * This is required over a afterEach hook as we need the environment instance passed.
    */
  def testFinished(testName: String, environment: TCE): Unit = {}

  override val loggerFactory: SuppressingLogger = SuppressingLogger(getClass)

  def logsToBeHandledAtStartup: Option[Seq[LogEntry] => Assertion] = None

  protected def handleStartupLogs[T](start: => T): T =
    logsToBeHandledAtStartup
      .map(assertion => loggerFactory.assertLoggedWarningsAndErrorsSeq(start, assertion))
      .getOrElse(start)

  /** Creates a new environment manually for a test without concurrent environment limitation and with optional config transformation.
    *
    * @param initialConfig specifies which configuration to start from with the default being a NEW one created
    *                      from the current environment.
    * @param configTransform a function that applies changes to the initial configuration
    *                        (with the plugins applied on top)
    * @param runPlugins a function that expects a plugin reference and returns whether or not it's supposed to be run
    *                   against the initial configuration
    * @return a new test console environment
    */
  protected def manualCreateEnvironment(
      initialConfig: E#Config = envDef.generateConfig,
      configTransform: E#Config => E#Config = identity,
      runPlugins: EnvironmentSetupPlugin[E, TCE] => Boolean = _ => true,
      testName: Option[String],
  ): TCE = TraceContext.withNewTraceContext { tc =>
    logger.debug(
      s"Starting creating environment for $suiteName${testName.fold("")(n => s", test '$n'")}:"
    )(tc)
    import org.scalactic.source
    def step[T](name: String)(expr: => T)(implicit pos: source.Position): T = {
      logger.debug(s"Starting creating environment: $name")(tc)
      Try(expr) match {
        case scala.util.Success(value) =>
          logger.debug(s"Finished creating environment: $name")(tc)
          value
        case scala.util.Failure(exception) =>
          val loc = s"(${pos.fileName}:${pos.lineNumber})"
          logger.error(s"Failed creating environment: $name $loc", exception)(tc)
          throw EnvironmentSetupException(
            s"Creating environment failed at step $name $loc",
            exception,
          )
      }
    }
    val metrics = testInfrastructureEnvironmentMetrics(testName)
    val testConfig = initialConfig
    // note: beforeEnvironmentCreate may well have side-effects (e.g. starting databases or docker containers)
    val pluginConfig = step("Running plugins before") {
      Timed.value(
        metrics.environmentCreatePluginsBefore,
        plugins.foldLeft(testConfig)((config, plugin) =>
          if (runPlugins(plugin))
            plugin.beforeEnvironmentCreated(config)
          else
            config
        ),
      )
    }

    // Once all the plugins and config transformation is done apply the defaults
    val finalConfig = step("Applying config transforms") {
      configTransform(pluginConfig).withDefaults(new DefaultPorts())
    }

    val scopedMetricsFactory = new ScopedInMemoryMetricsFactory
    val environmentFixture = step("Creating fixture") {
      Timed.value(
        metrics.environmentCreateFixture,
        envDef.environmentFactory.create(
          finalConfig,
          loggerFactory,
          envDef.testingConfig.copy(
            metricsFactoryType =
              /* If metrics reporters were configured for the test then it's an externally observed test
               * therefore actual metrics have to be reported.
               * The in memory metrics are used when no reporters are configured and the metrics are
               * observed directly in the test scenarios.
               *
               * In this case, you can grab the metrics from the [[MetricsRegistry.generateMetricsFactory]] method,
               * which is accessible using env.environment.metricsRegistry
               *
               * */
              if (finalConfig.monitoring.metrics.reporters.isEmpty)
                MetricsFactoryType.InMemory(scopedMetricsFactory)
              else MetricsFactoryType.External,
            initializeGlobalOpenTelemetry = false,
            sequencerTransportSeed = Some(1L),
          ),
        ),
      )
    }

    try {
      val testEnvironment: TCE = step("Creating test console") {
        envDef.createTestConsole(environmentFixture, loggerFactory)
      }

      // In tests, we want to retry some commands to avoid flakiness
      def commandRetryPolicy(command: GrpcAdminCommand[?, ?, ?])(error: GrpcError): Boolean = {
        val decodedCantonError = command match {
          // Command submissions are safe to retry - they are deduplicated by command ID
          case _: CommandSubmissionService.BaseCommand[?, ?, ?] => error.decodedCantonError
          case _: CommandService.BaseCommand[?, ?, ?] => error.decodedCantonError
          // Package operations are idempotent so we can retry them
          case _: ParticipantAdminCommands.Package.PackageCommand[?, ?, ?] =>
            error.decodedCantonError
          // Other commands might not be idempotent
          case _ => None
        }
        // Ideally we would reuse the logic from RetryProvider.RetryableError but that produces a circular dependency
        // so for now we go for an ad-hoc logic here.
        val shouldRetry = decodedCantonError.exists { err =>
          // Ideally we'd `case CantonGrpcUtil.GrpcErrors.AbortedDueToShutdown => true`
          // but unfortunately `error.decodedCantonError` appears to wrap it in a `GenericErrorCode`, so the match doesn't work.
          // We also cannot pattern match on GenericErrorCode because it's a private class.
          if (
            err.code.id == CantonGrpcUtil.GrpcErrors.AbortedDueToShutdown.id && err.code.category == CantonGrpcUtil.GrpcErrors.AbortedDueToShutdown.category
          ) {
            true
          } else {
            err.isRetryable
          }
        }

        logger.debug(
          s"Got canton error $decodedCantonError from command $command. Retrying: $shouldRetry"
        )(tc)

        shouldRetry
      }

      step("Updating retry policy") {
        testEnvironment.grpcLedgerCommandRunner.setRetryPolicy(commandRetryPolicy)
        testEnvironment.grpcAdminCommandRunner.setRetryPolicy(commandRetryPolicy)
      }

      step("Running plugins after") {
        Timed.value(
          metrics.environmentCreatePluginsAfter,
          plugins.foreach(plugin =>
            if (runPlugins(plugin)) plugin.afterEnvironmentCreated(finalConfig, testEnvironment)
          ),
        )
      }

      if (!finalConfig.parameters.manualStart)
        step("Starting all nodes") {
          handleStartupLogs(testEnvironment.startAll())
        }

      step("Running setup") {
        envDef.setups.foreach(setup => setup(testEnvironment))
      }

      testEnvironment
    } catch {
      case NonFatal(ex) =>
        // attempt to ensure the environment is shutdown if anything in the startup of initialization fails
        try {
          environmentFixture.close()
        } catch {
          case NonFatal(shutdownException) =>
            // we suppress the exception thrown by the shutdown as we want to propagate the original
            // exception, however add it to the suppressed list on this thrown exception
            ex.addSuppressed(shutdownException)
        }
        // rethrow exception
        throw ex
    }
  }

  protected def createEnvironment(testName: Option[String]): TCE = {
    val metrics = testInfrastructureEnvironmentMetrics(testName)
    val waitBegin = System.nanoTime()
    ConcurrentEnvironmentLimiter.create(getClass.getName, numPermits) {
      val waitEnd = System.nanoTime()
      metrics.environmentWait.update(waitEnd - waitBegin, TimeUnit.NANOSECONDS)
      Timed.value(metrics.environmentCreate, manualCreateEnvironment(testName = testName))
    }
  }

  protected def manualDestroyEnvironment(environment: TCE): Unit = {
    val config = environment.actualConfig
    plugins.foreach(_.beforeEnvironmentDestroyed(config, environment))
    try {
      environment.close()
    } finally {
      envDef.teardown(())
      plugins.foreach(_.afterEnvironmentDestroyed(config))
    }
  }

  protected def destroyEnvironment(testName: Option[String], environment: TCE): Unit = {
    val metrics = testInfrastructureEnvironmentMetrics(testName)
    environment.verifyParticipantLapiIntegrity(plugins)
    ConcurrentEnvironmentLimiter.destroy(getClass.getName, numPermits) {
      Timed.value(metrics.environmentDestroy, manualDestroyEnvironment(environment))
    }
  }

  /** number of permits required by this test
    *
    * this can be used for heavy tests to ensure that we have less other tests running concurrently
    */
  protected def numPermits: PositiveInt = PositiveInt.one

}

object EnvironmentSetup {
  final case class EnvironmentSetupException(message: String, cause: Throwable)
      extends RuntimeException(message, cause)
      with NoStackTrace // Stack trace is just a lot of scalatest and EnvironmentSetup
}

/** Starts an environment in a beforeAll test and uses it for all tests.
  * Destroys it in an afterAll hook.
  */
trait SharedEnvironment[E <: Environment, TCE <: TestConsoleEnvironment[E]]
    extends EnvironmentSetup[E, TCE]
    with CloseableTest {
  this: Suite with HasEnvironmentDefinition[E, TCE] with IntegrationTestMetrics with NamedLogging =>

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var sharedEnvironment: Option[TCE] = None

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    sharedEnvironment = Some(createEnvironment(None))
  }

  override def afterAll(): Unit =
    try {
      sharedEnvironment.foreach(destroyEnvironment(None, _))
    } finally super.afterAll()

  override def provideEnvironment(testName: String): TCE =
    sharedEnvironment.getOrElse(
      sys.error("beforeAll should have run before providing a shared environment")
    )
}

/** Creates an environment for each test. */
trait IsolatedEnvironments[E <: Environment, TCE <: TestConsoleEnvironment[E]]
    extends EnvironmentSetup[E, TCE] {
  this: Suite with HasEnvironmentDefinition[E, TCE] with IntegrationTestMetrics with NamedLogging =>

  override def provideEnvironment(testName: String): TCE = createEnvironment(Some(testName))
  override def testFinished(testName: String, environment: TCE): Unit = destroyEnvironment(
    Some(testName),
    environment,
  )
}
