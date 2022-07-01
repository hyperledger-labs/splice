package com.daml.network.integration

import better.files.Resource
import com.daml.network.config.CoinConfig
import com.daml.network.environment.{
  CoinConsoleEnvironment,
  CoinEnvironmentFactory,
  CoinEnvironmentImpl,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.config.TestingConfigInternal
import com.digitalasset.canton.environment.EnvironmentFactory
import com.digitalasset.canton.integration.{
  BaseEnvironmentDefinition,
  TestConsoleEnvironment,
  TestEnvironment,
}
import com.digitalasset.canton.console.TestConsoleOutput
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.typesafe.config.ConfigFactory
import monocle.macros.syntax.lens._

/** Analogue to Canton's CommunityEnvironmentDefinition. */
case class CoinEnvironmentDefinition(
    override val baseConfig: CoinConfig,
    override val testingConfig: TestingConfigInternal,
    override val setup: CoinTestConsoleEnvironment => Unit = _ => (),
    override val teardown: Unit => Unit = _ => (),
    override val configTransforms: Seq[CoinConfig => CoinConfig] = CoinConfigTransforms.defaults,
) extends BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment](
      baseConfig,
      testingConfig,
      setup,
      teardown,
      configTransforms,
    ) {
  def withManualStart: CoinEnvironmentDefinition =
    copy(baseConfig = baseConfig.focus(_.parameters.manualStart).replace(true))
  def withSetup(setup: CoinTestConsoleEnvironment => Unit): CoinEnvironmentDefinition =
    copy(setup = setup)
  def clearConfigTransforms(): CoinEnvironmentDefinition = copy(configTransforms = Seq())
  def addConfigTransforms(
      transforms: CoinConfig => CoinConfig*
  ): CoinEnvironmentDefinition =
    transforms.foldLeft(this)((ed, ct) => ed.addConfigTransform(ct))
  def addConfigTransform(
      transform: CoinConfig => CoinConfig
  ): CoinEnvironmentDefinition =
    copy(configTransforms = this.configTransforms :+ transform)

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
  lazy val simpleTopology: CoinEnvironmentDefinition =
    fromResource("simple-topology.conf")

  def fromResource(path: String): CoinEnvironmentDefinition =
    CoinEnvironmentDefinition(
      baseConfig = loadConfigFromResource(path),
      testingConfig = TestingConfigInternal(),
      configTransforms = Seq(),
    )

  private def loadConfigFromResource(path: String): CoinConfig = {
    val rawConfig = ConfigFactory.parseString(Resource.getAsString(path))
    CoinConfig.loadOrExit(rawConfig)
  }
}
