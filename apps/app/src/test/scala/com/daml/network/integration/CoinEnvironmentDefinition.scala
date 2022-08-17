package com.daml.network.integration

import better.files.{File, Resource}
import com.daml.network.config.CoinConfig
import com.daml.network.environment.{
  CoinConsoleEnvironment,
  CoinEnvironmentFactory,
  CoinEnvironmentImpl,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.console.TestConsoleOutput
import com.digitalasset.canton.environment.EnvironmentFactory
import com.digitalasset.canton.integration.{
  BaseEnvironmentDefinition,
  TestConsoleEnvironment,
  TestEnvironment,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.typesafe.config.ConfigFactory
import monocle.macros.syntax.lens._

/** Analogue to Canton's CommunityEnvironmentDefinition. */
case class CoinEnvironmentDefinition(
    override val baseConfig: CoinConfig,
    override val testingConfig: TestingConfigInternal = TestingConfigInternal(),
    override val setup: CoinTestConsoleEnvironment => Unit = _ => (),
    override val teardown: Unit => Unit = _ => (),
    val context: String, // String context included in generation of unique names. This could, e.g., be the test suite name
    val configTransformsWithContext: (String => Seq[CoinConfig => CoinConfig]) =
      CoinConfigTransforms.defaults(_),
) extends BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment](
      baseConfig,
      testingConfig,
      setup,
      teardown,
      configTransformsWithContext(context),
    ) {
  override val configTransforms = configTransformsWithContext(context)
  def withManualStart: CoinEnvironmentDefinition =
    copy(baseConfig = baseConfig.focus(_.parameters.manualStart).replace(true))
  def withSetup(setup: CoinTestConsoleEnvironment => Unit): CoinEnvironmentDefinition =
    copy(setup = setup)
  def clearConfigTransforms(): CoinEnvironmentDefinition =
    copy(configTransformsWithContext = _ => Seq())
  def addConfigTransforms(
      transforms: (String, CoinConfig) => CoinConfig*
  ): CoinEnvironmentDefinition =
    transforms.foldLeft(this)((ed, ct) => ed.addConfigTransform(ct))
  def addConfigTransform(
      transform: (String, CoinConfig) => CoinConfig
  ): CoinEnvironmentDefinition =
    copy(configTransformsWithContext =
      ctx => this.configTransformsWithContext(ctx) :+ (conf => transform(ctx, conf))
    )

  override lazy val environmentFactory: EnvironmentFactory[CoinEnvironmentImpl] =
    CoinEnvironmentFactory

  override def createTestConsole(
      environment: CoinEnvironmentImpl,
      loggerFactory: NamedLoggerFactory,
  ): TestConsoleEnvironment[CoinEnvironmentImpl] =
    new CoinConsoleEnvironment(
      environment,
      new TestConsoleOutput(loggerFactory),
    ) with TestEnvironment[CoinEnvironmentImpl] {
      override val actualConfig: CoinConfig = environment.config
    }
}

object CoinEnvironmentDefinition {
  def simpleTopology(testName: String): CoinEnvironmentDefinition =
    fromResource("simple-topology.conf", testName)

  def fromResource(path: String, testName: String): CoinEnvironmentDefinition =
    CoinEnvironmentDefinition(
      baseConfig = loadConfigFromResource(path),
      context = testName,
    )

  private def loadConfigFromResource(path: String): CoinConfig = {
    val rawConfig = ConfigFactory.parseString(Resource.getAsString(path))
    CoinConfig.loadOrExit(rawConfig)
  }

  def fromFiles(testName: String, files: File*): CoinEnvironmentDefinition = {
    val config = CoinConfig.parseAndLoadOrExit(files.map(_.toJava))
    CoinEnvironmentDefinition(baseConfig = config, context = testName)
  }
}
