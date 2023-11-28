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
import com.daml.network.util.CommonCNNodeAppInstanceReferences
import com.digitalasset.canton.admin.api.client.data.User
import com.digitalasset.canton.config.RequireTypes.{NonNegativeNumeric, PositiveInt}
import com.digitalasset.canton.config.{
  ClockConfig,
  NonNegativeFiniteDuration,
  TestingConfigInternal,
}
import com.digitalasset.canton.console.TestConsoleOutput
import com.digitalasset.canton.environment.EnvironmentFactory
import com.digitalasset.canton.integration.{
  BaseEnvironmentDefinition,
  TestConsoleEnvironment,
  TestEnvironment,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, SuppressingLogger}
import com.digitalasset.canton.topology.transaction.UnionspaceDefinitionX
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.ConfigFactory
import monocle.macros.syntax.lens.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inside, Inspectors, OptionValues}

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
    with OptionValues
    with Inside {
  override def loggerFactory: SuppressingLogger = SuppressingLogger(getClass)
  override val configTransforms = configTransformsWithContext(context)

  def withManualStart: CNNodeEnvironmentDefinition = {
    this
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // We manually start apps so we disable the default setup
      // that blocks on all apps being initialized.
      .copy(setup = _ => ())
  }

  def withAllocatedUsers(
      extraIgnoredValidatorPrefixes: Seq[String] = Seq.empty
  ): CNNodeEnvironmentDefinition =
    copy(preSetup = env => {
      import env.*
      this.preSetup(env)
      svs.local.foreach(sv => {
        if (!sv.name.endsWith("Onboarded") && !sv.name.endsWith("Local")) {
          CNNodeEnvironmentDefinition.withAllocatedAdminUser(
            sv.config.ledgerApiUser,
            sv.participantClientWithAdminToken,
          )
        }
      })
      validators.local.foreach(validator => {
        if (
          !validator.name.startsWith("sv") && !validator.name
            .endsWith("Local") && !extraIgnoredValidatorPrefixes.exists(validator.name.startsWith)
        ) {
          CNNodeEnvironmentDefinition.withAllocatedValidatorUser(validator)
        }
      })
    })

  def withResettedUnionspace(): CNNodeEnvironmentDefinition = {
    copy(preSetup = env => {
      import env.*
      this.preSetup(env)

      /** The unionspace is reset to contain only sv1 after each env is used
        * When onboarding SVs their participant namespace is added to the unionspace, so for a "clean" test we have to remove them
        * We cannot just drop the unionspace because the global domain is owned by it
        */
      svs.local
        .find(_.name == "sv1")
        .foreach { sv1 =>
          val participantNamespace = sv1.participantClientWithAdminToken.id.uid.namespace
          val unionspace = UnionspaceDefinitionX.computeNamespace(Set(participantNamespace))
          val sv1Party = participantNamespace
          sv1.participantClientWithAdminToken.domains
            .list_connected()
            .find(_.domainAlias == sv1.config.domains.global.alias)
            .fold(
              logger.info("Not resetting unionspace as the domain is not connected")(
                TraceContext.empty
              )
            ) { domainId =>
              val store = domainId.domainId.filterString
              sv1.participantClientWithAdminToken.topology.unionspaces
                .list(
                  store,
                  filterNamespace = unionspace.toProtoPrimitive,
                )
                .headOption
                .fold(
                  logger.info("Not resetting unionspace as it doesn't exist yet")(
                    TraceContext.empty
                  )
                ) { existingUnionspace =>
                  logger.info("Resetting unionspace to contain only sv1")(
                    TraceContext.empty
                  )
                  val ownersThatMustBeRemoved = existingUnionspace.item.owners.diff(Set(sv1Party))
                  if (ownersThatMustBeRemoved.nonEmpty) {
                    def proposeUnionspaceReset(client: CNParticipantClientReference): Unit = {
                      client.topology.unionspaces
                        .propose(
                          Set(sv1Party.fingerprint),
                          PositiveInt.one,
                          store,
                          serial = Some(existingUnionspace.context.serial + PositiveInt.one),
                        )
                        .discard
                    }
                    existingUnionspace.item.owners.foreach { owner =>
                      svs.local
                        .map(_.participantClientWithAdminToken)
                        .find(_.id.uid.namespace == owner)
                        .foreach(proposeUnionspaceReset)
                    }
                  }
                }
            }
        }
    })
  }

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

  def withoutInitialManagerApps: CNNodeEnvironmentDefinition = {
    addConfigTransform((_, config) =>
      config.copy(validatorApps = config.validatorApps.map { case (name, config) =>
        name -> config.copy(appManager =
          config.appManager.map(
            _.copy(initialInstalledApps = Map.empty, initialRegisteredApps = Map.empty)
          )
        )
      })
    )
  }

  def withoutAutomaticRewardsCollectionAndCoinMerging: CNNodeEnvironmentDefinition =
    addConfigTransform((_, config) =>
      CNNodeConfigTransforms.updateAllAutomationConfigs(
        _.focus(_.enableAutomaticRewardsCollectionAndCoinMerging).replace(false)
      )(config)
    )

  def withoutLeaderReplacement: CNNodeEnvironmentDefinition =
    addConfigTransform((_, config) =>
      CNNodeConfigTransforms.updateAllAutomationConfigs(
        _.focus(_.enableLeaderReplacementTrigger).replace(false)
      )(config)
    )

  def withHttpSettingsForHigherThroughput: CNNodeEnvironmentDefinition =
    addConfigTransform((_, config) =>
      config.copy(pekkoConfig =
        Some(
          ConfigFactory.parseString(
            """
              |pekko.http.host-connection-pool {
              |  max-connections = 1000
              |  min-connections = 20
              |  max-open-requests = 1024
              |}
              |""".stripMargin
          )
        )
      )
    )

  private def withTrafficTopupsEnabled: CNNodeEnvironmentDefinition =
    addConfigTransform((_, config) =>
      CNNodeConfigTransforms.updateAllValidatorConfigs { case (name, validatorConfig) =>
        val domainFeesEnabledConfig = validatorConfig
          // reduce top-up interval from default of 10m so that we can see
          // multiple top-ups in wall-clock tests
          .focus(_.domains.global.buyExtraTraffic.minTopupInterval)
          .replace(NonNegativeFiniteDuration.ofMinutes(1))
        if (name.startsWith("sv"))
          domainFeesEnabledConfig
            .focus(_.domains.global.buyExtraTraffic.targetThroughput)
            .replace(NonNegativeNumeric.tryCreate(BigDecimal(80000)))
        else if (name.contains("bob"))
          domainFeesEnabledConfig
            .focus(_.domains.global.buyExtraTraffic.targetThroughput)
            .replace(NonNegativeNumeric.tryCreate(BigDecimal(0)))
        else
          domainFeesEnabledConfig
            .focus(_.domains.global.buyExtraTraffic.targetThroughput)
            .replace(NonNegativeNumeric.tryCreate(BigDecimal(30000)))
      }(config)
    )

  def withTrafficTopupsDisabled: CNNodeEnvironmentDefinition =
    addConfigTransform((_, config) =>
      CNNodeConfigTransforms.updateAllValidatorConfigs_(config =>
        config
          .focus(_.domains.global.buyExtraTraffic.targetThroughput)
          .replace(NonNegativeNumeric.tryCreate(0))
      )(config)
    )

  def withSequencerConnectionsFromScanDisabled(
      sequencerPortBump: Int = 0
  ): CNNodeEnvironmentDefinition =
    addConfigTransform((_, config) =>
      CNNodeConfigTransforms.updateAllValidatorConfigs_(config =>
        config
          .focus(_.domains.global.url)
          .replace(Some(s"http://localhost:${5008 + sequencerPortBump}"))
      )(config)
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

  private def withSimTime: CNNodeEnvironmentDefinition =
    addConfigTransformsToFront((_, conf) =>
      conf
        .focus(_.parameters.clock)
        .replace(
          ClockConfig.RemoteClock(
            // This reads the right port as the bump is added to the front
            conf.svApps.values.headOption
              .getOrElse(throw new IllegalArgumentException("expected a sv app to be configured"))
              .participantClient
              .clientAdminApi
          )
        )
    )
      .addConfigTransformsToFront(
        (_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(10_000)(conf),
        (_, conf) => CNNodeConfigTransforms.bumpCantonDomainPortsBy(10_000)(conf),
      )
      // we bump remote app ports separately in order to not confuse
      // the PreflightIntegrationTest which also uses bumpCantonPortsBy
      .addConfigTransformsToFront((_, conf) =>
        CNNodeConfigTransforms.bumpDirectoryClientsPortsBy(10_000)(conf)
      )
      .addConfigTransformsToFront((_, conf) =>
        CNNodeConfigTransforms.bumpRemoteSplitwellPortsBy(10_000)(conf)
      )
      .withTrafficTopupsDisabled
      .withSequencerConnectionsFromScanDisabled(10_000)

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
      override val actorSystem = super[TestEnvironment].actorSystem
      override val actualConfig: CNNodeConfig = environment.config

    }
}

object CNNodeEnvironmentDefinition extends CommonCNNodeAppInstanceReferences {

  // Prefer this to `4Svs` for better test performance (unless your really need >1 SV of course).
  def simpleTopology1Sv(testName: String): CNNodeEnvironmentDefinition =
    fromResources(Seq("simple-topology-1sv.conf"), testName)
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      .withResettedUnionspace()

  def simpleTopology4Svs(testName: String): CNNodeEnvironmentDefinition =
    fromResources(Seq("simple-topology.conf"), testName)
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      .withResettedUnionspace()

  def simpleTopology1SvWithSimTime(testName: String): CNNodeEnvironmentDefinition =
    simpleTopology1Sv(testName).withSimTime

  def simpleTopology4SvsWithSimTime(testName: String): CNNodeEnvironmentDefinition =
    simpleTopology4Svs(testName).withSimTime

  def preflightTopology(testName: String): CNNodeEnvironmentDefinition = {
    fromResource("preflight-topology.conf", testName).clearConfigTransforms()
  }

  def svPreflightTopology(testName: String): CNNodeEnvironmentDefinition = {
    fromResource("sv-preflight-topology.conf", testName).clearConfigTransforms()
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

  private def withAllocatedAdminUser(
      user: String,
      admin: com.digitalasset.canton.console.commands.BaseLedgerApiAdministration,
  ): User = {
    admin.ledger_api.users.create(
      id = user,
      actAs = Set.empty,
      primaryParty = None,
      readAs = Set.empty,
      participantAdmin = true,
    )
  }

  def withAllocatedValidatorUser(validator: ValidatorAppBackendReference): User =
    withAllocatedAdminUser(
      validator.config.ledgerApiUser,
      validator.participantClientWithAdminToken,
    )
}
