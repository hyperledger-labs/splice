// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.integration

import com.digitalasset.canton.{CloseableTest, TestEssentials}
import com.digitalasset.canton.environment.Environment
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.util.control.NonFatal

/** Provides an ability to create a canton environment when needed for test.
  * Include [[IsolatedEnvironments]] or [[SharedEnvironment]] to determine when this happens.
  * Uses [[ConcurrentEnvironmentLimiter]] to ensure we limit the number of concurrent environments in a test run.
  */
sealed trait EnvironmentSetup[E <: Environment, TCE <: TestConsoleEnvironment[E]]
    extends BeforeAndAfterAll {
  this: Suite with HasEnvironmentDefinition[E, TCE] with TestEssentials =>

  private lazy val envDef = environmentDefinition

  // plugins are registered during construction from a single thread
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var plugins: Seq[EnvironmentSetupPlugin[E, TCE]] = Seq()

  protected[integration] def registerPlugin(plugin: EnvironmentSetupPlugin[E, TCE]): Unit =
    plugins = plugins :+ plugin

  override protected def beforeAll(): Unit = {
    plugins.foreach(_.beforeTests())
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    try super.afterAll()
    finally plugins.foreach(_.afterTests())
  }

  /** Provide an environment for an individual test either by reusing an existing one or creating a new one
    * depending on the approach being used.
    */
  def provideEnvironment: TCE

  /** Optional hook for implementors to know when a test has finished and be provided the environment instance.
    * This is required over a afterEach hook as we need the environment instance passed.
    */
  def testFinished(environment: TCE): Unit = {}

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
  ): TCE = {
    val testConfig = initialConfig
    // note: beforeEnvironmentCreate may well have side-effects (e.g. starting databases or docker containers)
    val pluginConfig = {
      plugins.foldLeft(testConfig)((config, plugin) =>
        if (runPlugins(plugin))
          plugin.beforeEnvironmentCreated(config)
        else
          config
      )
    }
    val finalConfig = configTransform(pluginConfig)

    val environmentFixture =
      envDef.environmentFactory.create(
        finalConfig,
        loggerFactory,
        envDef.testingConfig.copy(initializeGlobalOpenTelemetry = false),
      )

    try {
      val testEnvironment: TCE =
        envDef.createTestConsole(environmentFixture, environmentFixture.loggerFactory)

      plugins.foreach(plugin =>
        if (runPlugins(plugin)) plugin.afterEnvironmentCreated(finalConfig, testEnvironment)
      )

      if (!finalConfig.parameters.manualStart)
        testEnvironment.startAll()

      envDef.setups.foreach(setup => setup(testEnvironment))

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

  protected def createEnvironment(): TCE =
    ConcurrentEnvironmentLimiter.create(getClass.getName)(manualCreateEnvironment())

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

  protected def destroyEnvironment(environment: TCE): Unit = {
    ConcurrentEnvironmentLimiter.destroy(getClass.getName) {
      manualDestroyEnvironment(environment)
    }
  }
}

/** Starts an environment in a beforeAll test and uses it for all tests.
  * Destroys it in an afterAll hook.
  */
trait SharedEnvironment[E <: Environment, TCE <: TestConsoleEnvironment[E]]
    extends EnvironmentSetup[E, TCE]
    with CloseableTest {
  this: Suite with HasEnvironmentDefinition[E, TCE] with TestEssentials =>

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var sharedEnvironmentO: Option[TCE] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    val env = createEnvironment()
    sharedEnvironmentO = Some(env)
    // Adjusting the logger to also include the config.name property
    // (not possible for isolated environments because we use `lazy val` for accessing the logger from the logger factory,
    // so after the first log line further changes to the logger factory have no effect on the logger)
    varLoggerFactory =
      env.actualConfig.name.fold(varLoggerFactory)(varLoggerFactory.append("config", _))
  }

  override def afterAll(): Unit =
    try {
      sharedEnvironmentO.foreach(destroyEnvironment)
    } finally super.afterAll()

  override def provideEnvironment: TCE =
    sharedEnvironmentO.getOrElse(
      sys.error("beforeAll should have run before providing a shared environment")
    )
}

/** Creates an environment for each test. */
trait IsolatedEnvironments[E <: Environment, TCE <: TestConsoleEnvironment[E]]
    extends EnvironmentSetup[E, TCE] {
  this: Suite with HasEnvironmentDefinition[E, TCE] with TestEssentials =>

  override def provideEnvironment: TCE = createEnvironment()
  override def testFinished(environment: TCE): Unit = destroyEnvironment(environment)
}
