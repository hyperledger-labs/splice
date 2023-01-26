package com.daml.network.integration

import better.files.{File, Resource}
import com.daml.network.config.{CoinConfig, CoinConfigTransforms}
import com.daml.network.environment.{
  CoinConsoleEnvironment,
  CoinEnvironmentFactory,
  CoinEnvironmentImpl,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.config.{ClockConfig, TestingConfigInternal}
import com.digitalasset.canton.console.TestConsoleOutput
import com.digitalasset.canton.environment.EnvironmentFactory
import com.digitalasset.canton.integration.{
  BaseEnvironmentDefinition,
  TestConsoleEnvironment,
  TestEnvironment,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.typesafe.config.ConfigFactory
import monocle.macros.syntax.lens.*

/** Analogue to Canton's CommunityEnvironmentDefinition. */
case class CoinEnvironmentDefinition(
    override val baseConfig: CoinConfig,
    override val testingConfig: TestingConfigInternal = TestingConfigInternal(),
    val preSetup: CoinTestConsoleEnvironment => Unit = _ => (),
    val setup: CoinTestConsoleEnvironment => Unit = _ => (),
    override val teardown: Unit => Unit = _ => (),
    val context: String, // String context included in generation of unique names. This could, e.g., be the test suite name
    val configTransformsWithContext: (String => Seq[CoinConfig => CoinConfig]) =
      CoinConfigTransforms.defaults(_),
) extends BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment](
      baseConfig,
      testingConfig,
      List(preSetup, setup),
      teardown,
      configTransformsWithContext(context),
    ) {
  override val configTransforms = configTransformsWithContext(context)
  def withManualStart: CoinEnvironmentDefinition =
    copy(baseConfig = baseConfig.focus(_.parameters.manualStart).replace(true))

  def withAllocatedSvcAndSvUsers(): CoinEnvironmentDefinition =
    copy(preSetup = env => {
      import env._
      this.preSetup(env)
      svcOpt.foreach(svc => {
        // TODO(M3-46) At some point the svcParty should be created even when `svcOpt == None`
        val svcParty =
          svc.remoteParticipantWithAdminToken.parties.enable(svc.config.ledgerApiUser)
        svc.remoteParticipantWithAdminToken.ledger_api.users.create(
          id = svc.config.ledgerApiUser,
          actAs = Set(svcParty.toLf),
          primaryParty = Some(svcParty.toLf),
          readAs = Set.empty,
          participantAdmin = true,
        )
        svs.local.foreach(sv => {
          val svParty =
            sv.remoteParticipantWithAdminToken.parties.enable(sv.config.ledgerApiUser)
          sv.remoteParticipantWithAdminToken.ledger_api.users.create(
            id = sv.config.ledgerApiUser,
            actAs =
              // the SV app will revoke the "act as svcParty" right at the end of its init
              if (sv.config.foundConsortium) Set(svParty.toLf, svcParty.toLf)
              else Set(svParty.toLf),
            primaryParty = Some(svParty.toLf),
            readAs = Set(svcParty.toLf),
            participantAdmin = true,
          )
        })
      })
    })
  def withAllocatedValidatorUsers(): CoinEnvironmentDefinition =
    copy(preSetup = env => {
      import env._
      this.preSetup(env)
      validators.local.foreach(validator => {
        val validatorParty =
          validator.remoteParticipantWithAdminToken.parties.enable(validator.config.ledgerApiUser)
        validator.remoteParticipantWithAdminToken.ledger_api.users.create(
          id = validator.config.ledgerApiUser,
          actAs = Set(validatorParty.toLf),
          primaryParty = Some(validatorParty.toLf),
          readAs = Set.empty,
          participantAdmin = true,
        )
      })
    })
  def withInitializedNodes(): CoinEnvironmentDefinition =
    copy(setup = implicit env => {
      this.setup(env)
      CoinEnvironmentDefinition.waitForNodeInitialization(env)
    })
  def withPreSetup(preSetup: CoinTestConsoleEnvironment => Unit): CoinEnvironmentDefinition =
    copy(preSetup = preSetup)

  /** Use exactly this setup and replace any previously existing setup. */
  def withThisSetup(setup: CoinTestConsoleEnvironment => Unit): CoinEnvironmentDefinition =
    copy(setup = setup)

  /** Add an extra setup step after the already registered setup */
  def withAdditionalSetup(setup: CoinTestConsoleEnvironment => Unit): CoinEnvironmentDefinition =
    copy(setup = env => {
      this.setup(env)
      setup(env)
    })

  /** Remove any previously registered setup */
  def withNoSetup(): CoinEnvironmentDefinition =
    copy(setup = _ => ())
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

  /** Apply these config transforms before all others configured so far. */
  def addConfigTransformsToFront(
      transforms: (String, CoinConfig) => CoinConfig*
  ): CoinEnvironmentDefinition =
    transforms.foldRight(this)((ct, ed) => ed.addConfigTransformToFront(ct))
  def addConfigTransformToFront(
      transform: (String, CoinConfig) => CoinConfig
  ): CoinEnvironmentDefinition =
    copy(configTransformsWithContext =
      ctx => (conf => transform(ctx, conf)) +: this.configTransformsWithContext(ctx)
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
      .withAllocatedSvcAndSvUsers()
      .withAllocatedValidatorUsers()
      .withInitializedNodes()

  def simpleTopologyWithSimTime(testName: String): CoinEnvironmentDefinition =
    simpleTopology(testName)
      // all of these transforms need to happen before the auth-related default transforms,
      // which use the `clock` parameter to determine which `.tokens` file to read
      // and the ledger API ports to identify the tokens in that file; hence we add them `ToFront`
      .addConfigTransformsToFront((_, conf) =>
        conf
          .focus(_.parameters.clock)
          .replace(
            ClockConfig.RemoteClock(
              // This reads the right port as the bump is added to the front
              conf.svcApp
                .getOrElse(throw new IllegalArgumentException("expected svc app to be configured"))
                .remoteParticipant
                .clientAdminApi
            )
          )
      )
      .addConfigTransformsToFront((_, conf) => CoinConfigTransforms.bumpCantonPortsBy(10_000)(conf))
      // we bump remote app ports separately in order to not confuse
      // the PreflightIntegrationTest which also uses bumpCantonPortsBy
      .addConfigTransformsToFront((_, conf) =>
        CoinConfigTransforms.bumpRemoteDirectoryPortsBy(10_000)(conf)
      )
      .addConfigTransformsToFront((_, conf) =>
        CoinConfigTransforms.bumpRemoteSplitwisePortsBy(10_000)(conf)
      )

  def fromResource(path: String, testName: String): CoinEnvironmentDefinition =
    CoinEnvironmentDefinition(
      baseConfig = loadConfigFromResource(path),
      context = testName,
    )

  private def loadConfigFromResource(path: String): CoinConfig = {
    val rawConfig = ConfigFactory.parseString(Resource.getAsString(path))
    CoinConfig.loadOrThrow(rawConfig)
  }

  def fromFiles(testName: String, files: File*): CoinEnvironmentDefinition = {
    val config = CoinConfig.parseAndLoadOrThrow(files.map(_.toJava))
    CoinEnvironmentDefinition(baseConfig = config, context = testName)
  }
  def waitForNodeInitialization(env: CoinConsoleEnvironment): Unit =
    env.coinNodes.local.foreach(_.waitForInitialization())
}
