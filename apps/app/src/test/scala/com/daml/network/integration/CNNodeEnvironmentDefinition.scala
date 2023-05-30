package com.daml.network.integration

import better.files.{File, Resource}
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.console.{CNParticipantClientReference, ValidatorAppBackendReference}
import com.daml.network.environment.{
  CNNodeConsoleEnvironment,
  CNNodeEnvironmentFactory,
  CNNodeEnvironmentImpl,
}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.sv.config.SvOnboardingConfig
import com.digitalasset.canton.admin.api.client.data.User
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
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.logging.SuppressingLogger

/** Analogue to Canton's CommunityEnvironmentDefinition. */
case class CNNodeEnvironmentDefinition(
    override val baseConfig: CNNodeConfig,
    override val testingConfig: TestingConfigInternal = TestingConfigInternal(),
    val preSetup: CNNodeTestConsoleEnvironment => Unit = _ => (),
    val setup: CNNodeTestConsoleEnvironment => Unit = _ => (),
    override val teardown: Unit => Unit = _ => (),
    val context: String, // String context included in generation of unique names. This could, e.g., be the test suite name
    val configTransformsWithContext: (String => Seq[CNNodeConfig => CNNodeConfig]) = (_: String) =>
      CNNodeConfigTransforms.defaults(),
) extends BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment](
      baseConfig,
      testingConfig,
      List(preSetup, setup),
      teardown,
      configTransformsWithContext(context),
    )
    with Inspectors
    with Matchers
    with NamedLogging
    with OptionValues {
  override def loggerFactory: SuppressingLogger = SuppressingLogger(getClass)
  override val configTransforms = configTransformsWithContext(context)

  def withManualStart: CNNodeEnvironmentDefinition = {
    this
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // We manually start apps so we disable the default setup
      // that blocks on all apps being initialized.
      .copy(setup = _ => ())
  }

  def withAllocatedSvcAndSvUsers(): CNNodeEnvironmentDefinition =
    copy(preSetup = env => {
      import env.*
      this.preSetup(env)
      svcOpt.foreach(svc => {
        // TODO(M3-46) At some point the svcParty should be created even when `svcOpt == None`
        val svcParty = CNNodeEnvironmentDefinition.allocateParty(
          svc.participantClientWithAdminToken,
          svc.config.ledgerApiUser,
        )
        svc.participantClientWithAdminToken.ledger_api.users.create(
          id = svc.config.ledgerApiUser,
          actAs = Set(svcParty),
          primaryParty = Some(svcParty),
          readAs = Set.empty,
          participantAdmin = true,
        )
        val svUserRegex = raw"sv(\d+)-(.*)".r
        svs.local.foreach(sv => {
          // We want to use the same party for the validator service party and the SV party. This is test only code
          // so we just find the matching validator user by relying on a specific naming format.
          val (svNumber, suffix) = sv.config.ledgerApiUser match {
            case svUserRegex(number, suffix) => (number, suffix)
            case user => fail(s"SV user name did not match expected format: $user")
          }
          val validatorUserName = s"sv${svNumber}_validator_user-$suffix"
          val user = sv.participantClientWithAdminToken.ledger_api.users
            .get(validatorUserName)
          val svParty = user.primaryParty.value
          sv.participantClientWithAdminToken.ledger_api.users.create(
            id = sv.config.ledgerApiUser,
            actAs = sv.config.onboarding match {
              case _: SvOnboardingConfig.FoundCollective => Set(svParty, svcParty)
              case _ => Set(svParty)
            },
            primaryParty = Some(svParty),
            readAs = Set.empty,
            participantAdmin = true,
          )
        })
        directories.local.foreach(directory =>
          svc.participantClientWithAdminToken.ledger_api.users.create(
            id = directory.config.ledgerApiUser,
            actAs = Set(svcParty),
            primaryParty = Some(svcParty),
          )
        )
      })
    })

  def withAllocatedValidatorUsers(): CNNodeEnvironmentDefinition =
    copy(preSetup = env => {
      import env.*
      this.preSetup(env)
      validators.local.foreach(validator => {
        CNNodeEnvironmentDefinition.withAllocatedValidator(validator)
      })
    })

  def withInitializedNodes(): CNNodeEnvironmentDefinition =
    copy(setup = implicit env => {
      this.setup(env)
      CNNodeEnvironmentDefinition.waitForNodeInitialization(env)
    })

  def withPreSetup(preSetup: CNNodeTestConsoleEnvironment => Unit): CNNodeEnvironmentDefinition =
    copy(preSetup = preSetup)

  /** Use exactly this setup and replace any previously existing setup. */
  def withThisSetup(setup: CNNodeTestConsoleEnvironment => Unit): CNNodeEnvironmentDefinition =
    copy(setup = setup)

  /** Add an extra setup step after the already registered setup */
  def withAdditionalSetup(
      setup: CNNodeTestConsoleEnvironment => Unit
  ): CNNodeEnvironmentDefinition =
    copy(setup = env => {
      this.setup(env)
      setup(env)
    })

  def withoutAutomaticRewardsCollectionAndCoinMerging: CNNodeEnvironmentDefinition =
    addConfigTransform((_, config) =>
      CNNodeConfigTransforms.updateAllAutomationConfigs(
        _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
      )(config)
    )

  def withHttpSettingsForHigherThroughput: CNNodeEnvironmentDefinition =
    addConfigTransform((_, config) =>
      config.copy(akkaConfig =
        Some(
          ConfigFactory.parseString(
            """
              |akka.http.host-connection-pool {
              |  max-connections = 1000
              |  min-connections = 20
              |  max-open-requests = 1024
              |}
              |""".stripMargin
          )
        )
      )
    )

  def withCoinPrice(price: BigDecimal): CNNodeEnvironmentDefinition =
    addConfigTransforms((_, conf) => CNNodeConfigTransforms.setCoinPrice(price)(conf))

  def clearConfigTransforms(): CNNodeEnvironmentDefinition =
    copy(configTransformsWithContext = _ => Seq())

  def addConfigTransforms(
      transforms: (String, CNNodeConfig) => CNNodeConfig*
  ): CNNodeEnvironmentDefinition =
    transforms.foldLeft(this)((ed, ct) => ed.addConfigTransform(ct))

  def addConfigTransform(
      transform: (String, CNNodeConfig) => CNNodeConfig
  ): CNNodeEnvironmentDefinition =
    copy(configTransformsWithContext =
      ctx => this.configTransformsWithContext(ctx) :+ (conf => transform(ctx, conf))
    )

  /** Apply these config transforms before all others configured so far. */
  def addConfigTransformsToFront(
      transforms: (String, CNNodeConfig) => CNNodeConfig*
  ): CNNodeEnvironmentDefinition =
    transforms.foldRight(this)((ct, ed) => ed.addConfigTransformToFront(ct))

  def addConfigTransformToFront(
      transform: (String, CNNodeConfig) => CNNodeConfig
  ): CNNodeEnvironmentDefinition =
    copy(configTransformsWithContext =
      ctx => (conf => transform(ctx, conf)) +: this.configTransformsWithContext(ctx)
    )

  override lazy val environmentFactory: EnvironmentFactory[CNNodeEnvironmentImpl] =
    CNNodeEnvironmentFactory

  override def createTestConsole(
      environment: CNNodeEnvironmentImpl,
      loggerFactory: NamedLoggerFactory,
  ): TestConsoleEnvironment[CNNodeEnvironmentImpl] =
    new CNNodeConsoleEnvironment(
      environment,
      new TestConsoleOutput(loggerFactory),
    ) with TestEnvironment[CNNodeEnvironmentImpl] {
      override val actualConfig: CNNodeConfig = environment.config
    }
}

object CNNodeEnvironmentDefinition {
  def simpleTopology(testName: String): CNNodeEnvironmentDefinition =
    fromResource("simple-topology.conf", testName)
      .withAllocatedValidatorUsers()
      .withAllocatedSvcAndSvUsers()
      .withInitializedNodes()

  def simpleTopologyX(testName: String): CNNodeEnvironmentDefinition =
    fromResources(Seq("simple-topology.conf", "x-node-overrides.conf"), testName)
      .withAllocatedValidatorUsers()
      .withAllocatedSvcAndSvUsers()
      .withInitializedNodes()

  def simpleTopologyWithSimTime(testName: String): CNNodeEnvironmentDefinition =
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
                .participantClient
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
        CNNodeConfigTransforms.bumpDirectoryClientsPortsBy(10_000)(conf)
      )
      .addConfigTransformsToFront((_, conf) =>
        CNNodeConfigTransforms.bumpRemoteSplitwellPortsBy(10_000)(conf)
      )

  def preflightTopology(testName: String): CNNodeEnvironmentDefinition = {
    fromResource("preflight-topology.conf", testName)
  }

  def fromResource(path: String, testName: String): CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition(
      baseConfig = loadConfigFromResources(path),
      context = testName,
    )

  def fromResources(paths: Seq[String], testName: String): CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition(
      baseConfig = loadConfigFromResources(paths: _*),
      context = testName,
    )

  private def loadConfigFromResources(paths: String*): CNNodeConfig = {
    val rawConfig = ConfigFactory.parseString(paths.map(Resource.getAsString(_)).mkString("\n"))
    CNNodeConfig.loadOrThrow(rawConfig)
  }

  def fromFiles(testName: String, files: File*): CNNodeEnvironmentDefinition = {
    val config = CNNodeConfig.parseAndLoadOrThrow(files.map(_.toJava))
    CNNodeEnvironmentDefinition(baseConfig = config, context = testName)
  }

  def empty(testName: String): CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition(CNNodeConfig.empty, context = testName)

  def waitForNodeInitialization(env: CNNodeConsoleEnvironment): Unit =
    env.coinNodes.local.foreach(_.waitForInitialization())

  def allocateParty(participant: CNParticipantClientReference, hint: String) =
    participant.ledger_api.parties.allocate(hint, hint).party

  def withAllocatedValidator(validator: ValidatorAppBackendReference): User = {
    val validatorParty = allocateParty(
      validator.participantClientWithAdminToken,
      validator.config.ledgerApiUser,
    )
    validator.participantClientWithAdminToken.ledger_api.users.create(
      id = validator.config.ledgerApiUser,
      actAs = Set(validatorParty),
      primaryParty = Some(validatorParty),
      readAs = Set.empty,
      participantAdmin = true,
    )
  }
}
