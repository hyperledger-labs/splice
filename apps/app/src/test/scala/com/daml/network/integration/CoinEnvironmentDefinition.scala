package com.daml.network.integration

import better.files.{File, Resource}
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.environment.{
  CoinConsoleEnvironment,
  CoinEnvironmentFactory,
  CoinEnvironmentImpl,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.sv.config.SvBootstrapConfig
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
    override val baseConfig: CNNodeConfig,
    override val testingConfig: TestingConfigInternal = TestingConfigInternal(),
    val preSetup: CoinTestConsoleEnvironment => Unit = _ => (),
    val setup: CoinTestConsoleEnvironment => Unit = _ => (),
    override val teardown: Unit => Unit = _ => (),
    val context: String, // String context included in generation of unique names. This could, e.g., be the test suite name
    val configTransformsWithContext: (String => Seq[CNNodeConfig => CNNodeConfig]) =
      CNNodeConfigTransforms.defaults(_),
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
      import env.*
      this.preSetup(env)
      svcOpt.foreach(svc => {
        // TODO(M3-46) At some point the svcParty should be created even when `svcOpt == None`
        val svcParty =
          svc.remoteParticipantWithAdminToken.ledger_api.parties
            .allocate(svc.config.ledgerApiUser, svc.config.ledgerApiUser)
            .party
        svc.remoteParticipantWithAdminToken.ledger_api.users.create(
          id = svc.config.ledgerApiUser,
          actAs = Set(svcParty),
          primaryParty = Some(svcParty),
          readAs = Set.empty,
          participantAdmin = true,
        )
        svs.local.foreach(sv => {
          val svParty = {
            sv.remoteParticipantWithAdminToken.ledger_api.parties
              .allocate(sv.config.ledgerApiUser, sv.config.ledgerApiUser)
              .party
          }
          sv.remoteParticipantWithAdminToken.ledger_api.users.create(
            id = sv.config.ledgerApiUser,
            actAs = sv.config.bootstrap match {
              case _: SvBootstrapConfig.FoundConsortium => Set(svParty, svcParty)
              case _ => Set(svParty)
            },
            primaryParty = Some(svParty),
            readAs = Set(svcParty),
            participantAdmin = true,
          )
        })
        directories.local.foreach(directory =>
          svc.remoteParticipantWithAdminToken.ledger_api.users.create(
            id = directory.config.ledgerApiUser,
            actAs = Set(svcParty),
            primaryParty = Some(svcParty),
          )
        )
      })
    })

  def withAllocatedValidatorUsers(): CoinEnvironmentDefinition =
    copy(preSetup = env => {
      import env.*
      this.preSetup(env)
      validators.local.foreach(validator => {
        val validatorParty =
          validator.remoteParticipantWithAdminToken.ledger_api.parties
            .allocate(validator.config.ledgerApiUser, validator.config.ledgerApiUser)
            .party
        validator.remoteParticipantWithAdminToken.ledger_api.users.create(
          id = validator.config.ledgerApiUser,
          actAs = Set(validatorParty),
          primaryParty = Some(validatorParty),
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
      transforms: (String, CNNodeConfig) => CNNodeConfig*
  ): CoinEnvironmentDefinition =
    transforms.foldLeft(this)((ed, ct) => ed.addConfigTransform(ct))

  def addConfigTransform(
      transform: (String, CNNodeConfig) => CNNodeConfig
  ): CoinEnvironmentDefinition =
    copy(configTransformsWithContext =
      ctx => this.configTransformsWithContext(ctx) :+ (conf => transform(ctx, conf))
    )

  /** Apply these config transforms before all others configured so far. */
  def addConfigTransformsToFront(
      transforms: (String, CNNodeConfig) => CNNodeConfig*
  ): CoinEnvironmentDefinition =
    transforms.foldRight(this)((ct, ed) => ed.addConfigTransformToFront(ct))

  def addConfigTransformToFront(
      transform: (String, CNNodeConfig) => CNNodeConfig
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
      override val actualConfig: CNNodeConfig = environment.config
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
      .addConfigTransformsToFront((_, conf) =>
        CNNodeConfigTransforms.bumpCantonPortsBy(10_000)(conf)
      )
      // we bump remote app ports separately in order to not confuse
      // the PreflightIntegrationTest which also uses bumpCantonPortsBy
      .addConfigTransformsToFront((_, conf) =>
        CNNodeConfigTransforms.bumpRemoteDirectoryPortsBy(10_000)(conf)
      )
      .addConfigTransformsToFront((_, conf) =>
        CNNodeConfigTransforms.bumpRemoteSplitwellPortsBy(10_000)(conf)
      )

  def preflightTopology(testName: String, networkAppsAddress: String): CoinEnvironmentDefinition = {
    fromResource("preflight-topology.conf", testName)
      .addConfigTransform((_, conf) =>
        CNNodeConfigTransforms.updateAllValidatorClientConfigs_(
          _.focus(_.adminApi.url)
            .modify(_.replace("${NETWORK_APPS_ADDRESS}", networkAppsAddress))
        )(conf)
      )
  }

  def fromResource(path: String, testName: String): CoinEnvironmentDefinition =
    CoinEnvironmentDefinition(
      baseConfig = loadConfigFromResource(path),
      context = testName,
    )

  private def loadConfigFromResource(path: String): CNNodeConfig = {
    val rawConfig = ConfigFactory.parseString(Resource.getAsString(path))
    CNNodeConfig.loadOrThrow(rawConfig)
  }

  def fromFiles(testName: String, files: File*): CoinEnvironmentDefinition = {
    val config = CNNodeConfig.parseAndLoadOrThrow(files.map(_.toJava))
    CoinEnvironmentDefinition(baseConfig = config, context = testName)
  }

  def empty(testName: String): CoinEnvironmentDefinition =
    CoinEnvironmentDefinition(CNNodeConfig.empty, context = testName)

  def waitForNodeInitialization(env: CoinConsoleEnvironment): Unit =
    env.coinNodes.local.foreach(_.waitForInitialization())
}
