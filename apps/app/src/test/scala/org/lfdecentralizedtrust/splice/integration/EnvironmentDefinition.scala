package org.lfdecentralizedtrust.splice.integration

import better.files.{File, Resource}
import com.digitalasset.canton.admin.api.client.data.User
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, NonNegativeNumeric}
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
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.typesafe.config.ConfigFactory
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, SpliceConfig}
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  ValidatorAppBackendReference,
}
import org.lfdecentralizedtrust.splice.environment.{
  DarResources,
  SpliceConsoleEnvironment,
  SpliceEnvironment,
  SpliceEnvironmentFactory,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.config.SvCantonIdentifierConfig
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.CommonAppInstanceReferences
import org.lfdecentralizedtrust.splice.validator.config.ValidatorCantonIdentifierConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inside, Inspectors, OptionValues}

/** Analogue to Canton's CommunityEnvironmentDefinition. */
case class EnvironmentDefinition(
    override val baseConfig: SpliceConfig,
    override val testingConfig: TestingConfigInternal = TestingConfigInternal(),
    val preSetup: SpliceTestConsoleEnvironment => Unit = _ => (),
    val setup: SpliceTestConsoleEnvironment => Unit = _ => (),
    override val teardown: Unit => Unit = _ => (),
    val context: String, // String context included in generation of unique names. This could, e.g., be the test suite name
    val configTransformsWithContext: (String => Seq[SpliceConfig => SpliceConfig]) = (_: String) =>
      ConfigTransforms.defaults(),
) extends BaseEnvironmentDefinition[SpliceConfig, SpliceEnvironment](
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
  override val configTransforms: Seq[SpliceConfig => SpliceConfig] = {
    // add the BFT sequencers config transform to the front if enabled to ensure that it modifies the ports before any other transforms updates the ports
    if (ConfigTransforms.IsTheCantonSequencerBFTEnabled)
      ConfigTransforms.withBftSequencers() +: this.configTransformsWithContext(context)
    else configTransformsWithContext(context)
  }

  def withManualStart: EnvironmentDefinition = {
    this
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // We manually start apps so we disable the default setup
      // that blocks on all apps being initialized.
      .copy(setup = _ => ())
  }

  def withAllocatedUsers(
      extraIgnoredSvPrefixes: Seq[String] = Seq.empty,
      extraIgnoredValidatorPrefixes: Seq[String] = Seq.empty,
  ): EnvironmentDefinition =
    copy(preSetup = env => {
      import env.*
      this.preSetup(env)
      svs.local.foreach(sv => {
        if (
          !sv.name.endsWith("Onboarded") && !sv.name.endsWith("Local") && !extraIgnoredSvPrefixes
            .exists(sv.name.startsWith)
        ) {
          EnvironmentDefinition.withAllocatedAdminUser(
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
          EnvironmentDefinition.withAllocatedValidatorUser(validator)
        }
      })
    })

  def withInitialPackageVersions: EnvironmentDefinition =
    addConfigTransforms(
      (_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(
            initialPackageConfig = InitialPackageConfig(
              amuletVersion = InitialPackageVersions.initialPackageVersion(DarResources.amulet),
              amuletNameServiceVersion =
                InitialPackageVersions.initialPackageVersion(DarResources.amuletNameService),
              dsoGovernanceVersion =
                InitialPackageVersions.initialPackageVersion(DarResources.dsoGovernance),
              validatorLifecycleVersion =
                InitialPackageVersions.initialPackageVersion(DarResources.validatorLifecycle),
              walletVersion = InitialPackageVersions.initialPackageVersion(DarResources.wallet),
              walletPaymentsVersion =
                InitialPackageVersions.initialPackageVersion(DarResources.walletPayments),
            )
          )
        )(config),
      (_, config) =>
        ConfigTransforms.updateAllValidatorAppConfigs_(c =>
          c.copy(
            appInstances = c.appInstances.transform {
              case ("splitwell", instance) =>
                instance.copy(dars =
                  Seq(
                    java.nio.file.Paths.get(
                      s"daml/dars/splitwell-${InitialPackageVersions.initialPackageVersion(DarResources.splitwell)}.dar"
                    )
                  )
                )
              case (_, instance) => instance
            }
          )
        )(config),
      (_, config) =>
        ConfigTransforms.updateAllSplitwellAppConfigs_(c =>
          c.copy(
            requiredDarVersion = PackageVersion.assertFromString(
              InitialPackageVersions.initialPackageVersion(DarResources.splitwell)
            )
          )
        )(config),
    )

  def withInitializedNodes(): EnvironmentDefinition =
    copy(setup = implicit env => {
      this.setup(env)
      EnvironmentDefinition.waitForNodeInitialization(env)
    })

  def withPreSetup(preSetup: SpliceTestConsoleEnvironment => Unit): EnvironmentDefinition =
    copy(preSetup = preSetup)

  def withNoVettedPackages(
      participants: SpliceTestConsoleEnvironment => Seq[ParticipantClientReference]
  ): EnvironmentDefinition = {
    copy(
      preSetup = implicit env => {
        this.preSetup(env)
        participants(env).foreach { p =>
          p.synchronizers.list_connected().foreach { connected =>
            val currentVettedPackages = p.topology.vetted_packages.list(store = Some(TopologyStoreId.Synchronizer(connected.synchronizerId)), filterParticipant = p.id.filterString)
            currentVettedPackages match {
              case Seq(mapping) if mapping.item.packages.length > 1 =>
                logger.info(s"Removing all vetted packages for ${p.name} on ${connected.synchronizerId}")(TraceContext.empty)
                p.topology.vetted_packages.propose(
                  p.id,
                  Seq.empty,
                  force = ForceFlags(ForceFlag.AllowUnvetPackageWithActiveContracts),
                  store = TopologyStoreId.Synchronizer(connected.synchronizerId),
                )
              case _ =>
                logger.info(s"No vetted packages for ${p.name} on ${connected.synchronizerId}")(TraceContext.empty)
            }
          }
        }
        participants(env).foreach { p =>
          logger.info(s"Ensuring vetting topology is effective for ${p.name}")(TraceContext.empty)
          p.topology.synchronisation.await_idle()
        }
      }
    )
  }

  /** Use exactly this setup and replace any previously existing setup. */
  def withThisSetup(setup: SpliceTestConsoleEnvironment => Unit): EnvironmentDefinition =
    copy(setup = setup)

  /** Add an extra setup step after the already registered setup */
  def withAdditionalSetup(
      setup: SpliceTestConsoleEnvironment => Unit
  ): EnvironmentDefinition =
    copy(setup = env => {
      this.setup(env)
      setup(env)
    })

  def withoutAutomaticRewardsCollectionAndAmuletMerging: EnvironmentDefinition =
    addConfigTransform((_, config) =>
      ConfigTransforms.updateAllAutomationConfigs(
        _.focus(_.enableAutomaticRewardsCollectionAndAmuletMerging).replace(false)
      )(config)
    )

  def withHttpSettingsForHigherThroughput: EnvironmentDefinition =
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

  def withTrafficTopupsEnabled: EnvironmentDefinition =
    addConfigTransform((_, config) =>
      ConfigTransforms.updateAllValidatorConfigs { case (name, validatorConfig) =>
        val domainFeesEnabledConfig = validatorConfig
          // reduce top-up interval from default of 10m so that we can see
          // multiple top-ups in wall-clock tests
          .focus(_.domains.global.buyExtraTraffic.minTopupInterval)
          .replace(NonNegativeFiniteDuration.ofMinutes(1))
        if (name.startsWith("sv"))
          domainFeesEnabledConfig
            .focus(_.domains.global.buyExtraTraffic.targetThroughput)
            .replace(NonNegativeNumeric.tryCreate(BigDecimal(0)))
        else
          domainFeesEnabledConfig
            .focus(_.domains.global.buyExtraTraffic.targetThroughput)
            .replace(NonNegativeNumeric.tryCreate(BigDecimal(100000)))
      }(config)
    )

  def withTrafficTopupsDisabled: EnvironmentDefinition =
    addConfigTransform((_, config) =>
      ConfigTransforms.updateAllValidatorConfigs_(config =>
        config
          .focus(_.domains.global.buyExtraTraffic.targetThroughput)
          .replace(NonNegativeNumeric.tryCreate(0))
      )(config)
    )

  def withTrafficBalanceCacheDisabled: EnvironmentDefinition =
    addConfigTransform((_, config) =>
      ConfigTransforms.updateAllValidatorConfigs_(config =>
        config
          .focus(_.domains.global.trafficBalanceCacheTimeToLive)
          .replace(NonNegativeFiniteDuration.ofMillis(0))
      )(config)
    )

  def withSequencerConnectionsFromScanDisabled(
      sequencerPortBump: Int = 0
  ): EnvironmentDefinition =
    addConfigTransform((_, config) =>
      ConfigTransforms.updateAllValidatorConfigs_(config =>
        config
          .focus(_.domains.global.url)
          .replace(Some(s"http://localhost:${5108 + sequencerPortBump}"))
      )(config)
    )

  def withBftSequencers: EnvironmentDefinition =
    addConfigTransformToFront((_, config) => ConfigTransforms.withBftSequencers()(config))

  def withAmuletPrice(price: BigDecimal): EnvironmentDefinition =
    addConfigTransforms((_, conf) => ConfigTransforms.setAmuletPrice(price)(conf))

  /** For an SV’s sequencer to be safely usable, we need to wait for participantResponseTimeout + mediatorResponseTimeout.
    * However, in some tests, we do care that an SV can connect to their own sequencer reasonably quickly.
    * To make that work, we lower the delay to a number that is not fully safe but empirically
    * long enough that all in-flight transactions succeed or fail before.
    */
  def unsafeWithSequencerAvailabilityDelay(
      duration: NonNegativeFiniteDuration
  ): EnvironmentDefinition =
    addConfigTransform((_, config) =>
      ConfigTransforms.updateAllSvAppConfigs_(
        _.focus(_.localSynchronizerNode)
          .modify(
            _.map(d =>
              d.focus(_.sequencer.sequencerAvailabilityDelay)
                .replace(duration)
            )
          )
      )(config)
    )

  def withOnboardingParticipantPromotionDelayEnabled(): EnvironmentDefinition = {
    addConfigTransform((_, config) =>
      ConfigTransforms.updateAllSvAppConfigs_(c =>
        c.focus(_.enableOnboardingParticipantPromotionDelay).replace(true)
      )(config)
    )
  }

  private def svNameFromValidatorName(name: String): Option[String] = {
    // Assumption: SV validators are named "sv<name>Validator"
    // Note that some tests create additional SVs, so <name> is not necessarily [1-4]
    "(sv.*)Validator".r.findFirstMatchIn(name) match {
      case Some(m) => Some(m.group(1))
      case None => None
    }
  }

  /** Configures all SV and validator apps to use a given suffix for their canton node identifiers.
    *
    * The canton node identifiers of shared canton nodes are defined in simple-topology-canton.conf (or similar),
    * and for tests, apps are configured in simple-topology.conf (or similar) to use those identifiers.
    *
    * This method is therefore only useful for tests that are using external canton instances
    */
  def withCantonNodeNameSuffix(suffix: String): EnvironmentDefinition =
    addConfigTransforms(
      (_, conf) =>
        ConfigTransforms.updateAllSvAppConfigs((svName, c) =>
          c.copy(
            cantonIdentifierConfig = Some(
              SvCantonIdentifierConfig(
                participant = svName + suffix,
                sequencer = svName + suffix,
                mediator = svName + suffix,
              )
            )
          )
        )(conf),
      (_, conf) =>
        ConfigTransforms.updateAllValidatorAppConfigs((validatorName, c) =>
          c.copy(
            cantonIdentifierConfig = Some(
              ValidatorCantonIdentifierConfig(
                participant = svNameFromValidatorName(validatorName) match {
                  case Some(svName) => svName + suffix
                  case None => validatorName + suffix
                }
              )
            )
          )
        )(conf),
    )

  /** e.g. to prevent ReceiveFaucetCouponTrigger from seeing stale caches */
  def withScanDisabledMiningRoundsCache(): EnvironmentDefinition =
    addConfigTransforms((_, config) =>
      ConfigTransforms.updateAllScanAppConfigs_(
        _.copy(miningRoundsCacheTimeToLiveOverride = Some(NonNegativeFiniteDuration.ofMillis(1)))
      )(config)
    )

  def clearConfigTransforms(): EnvironmentDefinition =
    copy(configTransformsWithContext = _ => Seq())

  def addConfigTransforms(
      transforms: (String, SpliceConfig) => SpliceConfig*
  ): EnvironmentDefinition =
    transforms.foldLeft(this)((ed, ct) => ed.addConfigTransform(ct))

  def addConfigTransform(
      transform: (String, SpliceConfig) => SpliceConfig
  ): EnvironmentDefinition =
    copy(configTransformsWithContext =
      ctx => this.configTransformsWithContext(ctx) :+ (conf => transform(ctx, conf))
    )

  /** Apply these config transforms before all others configured so far. */
  def addConfigTransformsToFront(
      transforms: (String, SpliceConfig) => SpliceConfig*
  ): EnvironmentDefinition =
    transforms.foldRight(this)((ct, ed) => ed.addConfigTransformToFront(ct))

  def addConfigTransformToFront(
      transform: (String, SpliceConfig) => SpliceConfig
  ): EnvironmentDefinition =
    copy(configTransformsWithContext =
      ctx => (conf => transform(ctx, conf)) +: this.configTransformsWithContext(ctx)
    )

  private def withSimTime: EnvironmentDefinition =
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
      .addConfigTransformsToFront((_, conf) => ConfigTransforms.bumpCantonPortsBy(10_000)(conf))
      .withTrafficTopupsDisabled
      .addConfigTransform((_, conf) =>
        ConfigTransforms
          .updateAllSvAppFoundDsoConfigs_(
            _.focus(_.initialSynchronizerFeesConfig.baseRateBurstAmount)
              .replace(NonNegativeLong.tryCreate(2_000_000L))
          )(conf)
      )
      .addConfigTransform((_, conf) =>
        ConfigTransforms
          .updateAllSvAppFoundDsoConfigs_(
            _.focus(_.topologyChangeDelayDuration)
              // same as canton for sim time
              .replace(NonNegativeFiniteDuration.Zero)
          )(conf)
      )
      .withSequencerConnectionsFromScanDisabled(10_000)

  override lazy val environmentFactory: EnvironmentFactory[SpliceConfig, SpliceEnvironment] =
    SpliceEnvironmentFactory

  override def createTestConsole(
      environment: SpliceEnvironment,
      loggerFactory: NamedLoggerFactory,
  ): TestConsoleEnvironment[SpliceConfig, SpliceEnvironment] =
    new SpliceConsoleEnvironment(
      environment,
      new TestConsoleOutput(loggerFactory),
    ) with TestEnvironment[SpliceConfig] {
      override val actorSystem = super[TestEnvironment].actorSystem
      override val actualConfig: SpliceConfig = this.environment.config

    }
}

object EnvironmentDefinition extends CommonAppInstanceReferences {

  // Prefer this to `4Svs` for better test performance (unless your really need >1 SV of course).
  def simpleTopology1Sv(testName: String): EnvironmentDefinition = {
    fromResources(Seq("simple-topology-1sv.conf"), testName)
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      .withInitialPackageVersions
  }

  def simpleTopology4Svs(testName: String): EnvironmentDefinition = {
    fromResources(Seq("simple-topology.conf"), testName)
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      .withInitialPackageVersions
  }

  def simpleTopology1SvWithSimTime(testName: String): EnvironmentDefinition =
    simpleTopology1Sv(testName).withSimTime

  def simpleTopology4SvsWithSimTime(testName: String): EnvironmentDefinition =
    simpleTopology4Svs(testName).withSimTime

  def preflightTopology(testName: String): EnvironmentDefinition = {
    fromResource("preflight-topology.conf", testName).clearConfigTransforms()
  }

  def svPreflightTopology(testName: String): EnvironmentDefinition = {
    fromResource("sv-preflight-topology.conf", testName).clearConfigTransforms()
  }

  def fromResource(path: String, testName: String): EnvironmentDefinition =
    EnvironmentDefinition(
      baseConfig = loadConfigFromResources(path),
      context = testName,
    )

  def fromResources(paths: Seq[String], testName: String): EnvironmentDefinition =
    EnvironmentDefinition(
      baseConfig = loadConfigFromResources(paths*),
      context = testName,
    )

  private def loadConfigFromResources(paths: String*): SpliceConfig = {
    val rawConfig = ConfigFactory.parseString(paths.map(Resource.getAsString(_)).mkString("\n"))
    SpliceConfig.loadOrThrow(rawConfig)
  }

  def fromFiles(testName: String, files: File*): EnvironmentDefinition = {
    val config = SpliceConfig.parseAndLoadOrThrow(files.map(_.toJava))
    EnvironmentDefinition(baseConfig = config, context = testName)
  }

  def empty(testName: String): EnvironmentDefinition =
    EnvironmentDefinition(SpliceConfig.empty, context = testName)

  def waitForNodeInitialization(env: SpliceConsoleEnvironment): Unit =
    env.amuletNodes.local.foreach(_.waitForInitialization())

  def allocateParty(participant: ParticipantClientReference, hint: String) =
    participant.ledger_api.parties.allocate(hint).party

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
