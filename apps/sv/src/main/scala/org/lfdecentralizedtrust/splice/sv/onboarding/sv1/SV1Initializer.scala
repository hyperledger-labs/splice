// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sv1

import cats.implicits.{
  catsSyntaxTuple2Semigroupal,
  catsSyntaxTuple3Semigroupal,
  catsSyntaxTuple4Semigroupal,
}
import cats.syntax.functorFilter.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.config.{
  EnabledFeaturesConfig,
  SpliceInstanceNamesConfig,
  UpgradesConfig,
}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.{
  AppStoreWithIngestion,
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.*
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.automation.{SvDsoAutomationService, SvSvAutomationService}
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftNode
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.sv.config.{
  SvAppBackendConfig,
  SvCantonIdentifierConfig,
  SvOnboardingConfig,
}
import org.lfdecentralizedtrust.splice.sv.onboarding.{
  DsoPartyHosting,
  NodeInitializerUtil,
  SetupUtil,
  SynchronizerNodeInitializer,
  SynchronizerNodeReconciler,
}
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler.SynchronizerNodeState
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.{
  ContractWithState,
  TemplateJsonDecoder,
  UploadablePackage,
}
import org.lfdecentralizedtrust.splice.util.SpliceUtil.{defaultAmuletConfig, defaultAnsConfig}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.SequencerAlias
import com.digitalasset.canton.config.SynchronizerTimeTrackerConfig
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnectionPoolDelays,
  SequencerConnections,
  TrafficControlParameters,
}
import com.digitalasset.canton.time.{
  Clock,
  NonNegativeFiniteDuration,
  PositiveFiniteDuration,
  PositiveSeconds,
}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransaction,
  StoredTopologyTransactions,
}
import com.digitalasset.canton.topology.transaction.{
  DecentralizedNamespaceDefinition,
  SignedTopologyTransaction,
  TopologyChangeOp,
  TopologyMapping,
}
import com.digitalasset.canton.topology.transaction.TopologyMapping.Code
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.jdk.OptionConverters.*
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

/** Container for the methods required by the SvApp to initialize sv1. */
class SV1Initializer(
    localSynchronizerNode: LocalSynchronizerNode,
    sv1Config: SvOnboardingConfig.FoundDso,
    participantId: ParticipantId,
    override protected val config: SvAppBackendConfig,
    upgradesConfig: UpgradesConfig,
    override protected val cometBftNode: Option[CometBftNode],
    override protected val ledgerClient: SpliceLedgerClient,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val clock: Clock,
    override protected val domainTimeSync: DomainTimeSynchronization,
    override protected val domainUnpausedSync: DomainUnpausedSynchronization,
    override protected val storage: DbStorage,
    override protected val retryProvider: RetryProvider,
    override protected val spliceInstanceNamesConfig: SpliceInstanceNamesConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    enabledFeatures: EnabledFeaturesConfig,
    svAcsStoreDescriptorUserVersion: Option[Long],
    dsoAcsStoreDescriptorUserVersion: Option[Long],
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
    mat: Materializer,
    tracer: Tracer,
    esf: ExecutionSequencerFactory,
    actorSystem: ActorSystem,
) extends NodeInitializerUtil {

  import SV1Initializer.bootstrapTransactionOrdering

  def bootstrapDso()(implicit
      tc: TraceContext
  ): Future[
    (
        SynchronizerId,
        DsoPartyHosting,
        SvSvStore,
        SvSvAutomationService,
        SvDsoStore,
        SvDsoAutomationService,
    )
  ] = {
    for {
      _ <- rotateGenesisGovernanceKeyForSV1(cometBftNode, sv1Config.name)
      initConnection = ledgerClient.readOnlyConnection(
        this.getClass.getSimpleName,
        loggerFactory,
      )
      cantonIdentifierConfig = config.cantonIdentifierConfig.getOrElse(
        SvCantonIdentifierConfig.default(config)
      )
      _ <-
        if (!config.shouldSkipSynchronizerInitialization) {
          SynchronizerNodeInitializer.initializeLocalCantonNodesWithNewIdentities(
            cantonIdentifierConfig,
            localSynchronizerNode,
            clock,
            loggerFactory,
            retryProvider,
          )
        } else {
          logger.info(
            "Skipping synchronizer node initialization because skipSynchronizerInitialization is enabled"
          )
          Future.unit
        }
      (namespace, synchronizerId) <-
        if (config.shouldSkipSynchronizerInitialization) {
          participantAdminConnection.getSynchronizerId(config.domains.global.alias).map { s =>
            (s.namespace, s)
          }
        } else {
          bootstrapDomain(localSynchronizerNode)
        }
      _ = logger.info("Domain is bootstrapped, connecting sv1 participant to domain")
      internalSequencerApi = localSynchronizerNode.sequencerInternalConfig
      _ <- participantAdminConnection.ensureDomainRegisteredAndConnected(
        SynchronizerConnectionConfig(
          config.domains.global.alias,
          sequencerConnections = SequencerConnections.tryMany(
            Seq(
              new GrpcSequencerConnection(
                NonEmpty.mk(Seq, LocalSynchronizerNode.toEndpoint(internalSequencerApi)),
                transportSecurity = internalSequencerApi.tlsConfig.isDefined,
                customTrustCertificates = None,
                SequencerAlias.Default,
                sequencerId = None,
              )
            ),
            PositiveInt.one,
            // We only have a single connection here.
            sequencerLivenessMargin = NonNegativeInt.zero,
            config.participantClient.sequencerRequestAmplification,
            // TODO(#2666) Make the delays configurable.
            sequencerConnectionPoolDelays = SequencerConnectionPoolDelays.default,
          ),
          manualConnect = false,
          synchronizerId = None,
          timeTracker = SynchronizerTimeTrackerConfig(
            minObservationDuration = config.timeTrackerMinObservationDuration,
            observationLatency = config.timeTrackerObservationLatency,
          ),
        ),
        newSequencerConnectionPool = config.parameters.enabledFeatures.newSequencerConnectionPool,
        overwriteExistingConnection =
          false, // The validator will manage sequencer connections after initial setup
        retryFor = RetryFor.WaitingOnInitDependency,
      )
      _ = logger.info("Participant connected to domain")
      _ <- ensureCantonNodesOTKRotatedIfNeeded(
        config.skipSynchronizerInitialization,
        cantonIdentifierConfig,
        Some(localSynchronizerNode),
        clock,
        loggerFactory,
        retryProvider,
        synchronizerId,
      )
      _ = logger.info("Synchronizer rotated OTK keys that were not signed")
      (dsoParty, svParty, _) <- (
        setupDsoParty(synchronizerId, initConnection, namespace),
        SetupUtil.setupSvParty(
          initConnection,
          config,
          participantAdminConnection,
        ),
        retryProvider.ensureThatB(
          RetryFor.WaitingOnInitDependency,
          "sv1_initial_package_vetting",
          "SV1 has uploaded the initial set of packages",
          initConnection
            .lookupUserMetadata(
              config.ledgerApiUser,
              BaseLedgerConnection.SV1_INITIAL_PACKAGE_UPLOAD_METADATA_KEY,
            )
            .map(_.nonEmpty), {
            val packages = requiredDars(sv1Config.initialPackageConfig)
            if (config.latestPackagesOnly)
              logger.warn(
                "latestPackagesOnly is enabled, only the latest versions of the initial packages will be uploaded and vetted"
              )
            logger.info(
              s"Starting with initial package ${sv1Config.initialPackageConfig} and vetting ${packages
                  .map(_.resourcePath)}"
            )
            participantAdminConnection
              .uploadDarFiles(
                packages,
                RetryFor.WaitingOnInitDependency,
              )
              .flatMap(_ =>
                participantAdminConnection
                  .vetDars(
                    synchronizerId,
                    packages.map(packageToVet => DarResource(packageToVet.resourcePath)),
                    None,
                    maxVettingDelay = None,
                  )
                  .flatMap { _ =>
                    initConnection.ensureUserMetadataAnnotation(
                      config.ledgerApiUser,
                      BaseLedgerConnection.SV1_INITIAL_PACKAGE_UPLOAD_METADATA_KEY,
                      "true",
                      RetryFor.WaitingOnInitDependency,
                    )
                  }
              )
          },
          logger,
        ),
      ).tupled
      storeKey = SvStore.Key(svParty, dsoParty)
      migrationInfo =
        DomainMigrationInfo(
          currentMigrationId = config.domainMigrationId, // Note: not guaranteed to be 0 for sv1
          migrationTimeInfo = None, // No previous migration, we're starting the network
        )
      svStore = newSvStore(storeKey, migrationInfo, participantId, svAcsStoreDescriptorUserVersion)
      dsoStore = newDsoStore(
        svStore.key,
        migrationInfo,
        participantId,
        dsoAcsStoreDescriptorUserVersion,
      )
      svAutomation = newSvSvAutomationService(
        svStore,
        dsoStore,
        ledgerClient,
        participantAdminConnection,
        Some(localSynchronizerNode),
      )
      connection = svAutomation.connection(SpliceLedgerConnectionPriority.Low)
      (_, decentralizedSynchronizer) <- (
        SetupUtil.ensureDsoPartyMetadataAnnotation(connection, config, dsoParty),
        svStore.domains.waitForDomainConnection(config.domains.global.alias),
      ).tupled
      _ <- SetupUtil.ensureSvNameMetadataAnnotation(
        connection,
        config,
        sv1Config.name,
      )
      _ <- DomainMigrationInfo.saveToUserMetadata(
        connection,
        config.ledgerApiUser,
        migrationInfo,
      )
      dsoPartyHosting = newDsoPartyHosting(storeKey.dsoParty)
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        decentralizedSynchronizer,
        connection,
        loggerFactory,
      )
      initialRound <- establishInitialRound(
        connection,
        upgradesConfig,
        packageVersionSupport,
        svParty,
      )
      // NOTE: we assume that DSO party, cometBft node, sequencer, and mediator nodes are initialized as
      // part of deployment and the running of bootstrap scripts. Here we just check that the DSO party
      // is allocated, as a stand-in for all of these actions.
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "dso_party_allocation",
        show"DSO party $dsoParty is allocated on participant $participantId and domain $decentralizedSynchronizer",
        for {
          dsoPartyIsAuthorized <- dsoPartyHosting.isDsoPartyAuthorizedOn(
            decentralizedSynchronizer,
            participantId,
          )
        } yield {
          if (dsoPartyIsAuthorized) ()
          else
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"DSO party is allocated on participant $participantId and domain $decentralizedSynchronizer"
              )
              .asRuntimeException()
        },
        logger,
      )
      dsoAutomation = newSvDsoAutomationService(
        svStore,
        dsoStore,
        Some(localSynchronizerNode),
        upgradesConfig,
        packageVersionSupport,
        enabledFeatures,
      )
      _ <- dsoStore.domains.waitForDomainConnection(config.domains.global.alias)
      withDsoStore = new WithDsoStore(
        dsoAutomation,
        decentralizedSynchronizer,
      )
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "bootstrap_dso_rules",
        show"the DsoRules and AmuletRules are bootstrapped",
        dsoStore.lookupDsoRules().map(_.isDefined), {
          withDsoStore.foundDso(initialRound, packageVersionSupport)
        },
        logger,
      )
      _ <- ensureCometBftGovernanceKeysAreSet(
        cometBftNode,
        svParty,
        dsoStore,
        dsoAutomation,
      )
      // Only start the triggers once DsoRules and AmuletRules have been bootstrapped
      _ = dsoAutomation.registerPostOnboardingTriggers()
      _ = dsoAutomation.registerTrafficReconciliationTriggers()
      _ = dsoAutomation.registerPostUnlimitedTrafficTriggers()
      _ <- checkIsOnboardedAndInDecentralizedNamespace(
        dsoStore
      )
      // The previous foundDso step will set the domain node config if DsoRules is not yet bootstrapped.
      // This is for the case that DsoRules is already bootstrapped but setting the domain node config is required,
      // for example if sv1 restarted after bootstrapping the DsoRules.
      // We only set the domain sequencer config if the existing one is different here.
      _ <-
        if (!config.shouldSkipSynchronizerInitialization) {
          withDsoStore.reconcileSequencerConfigIfRequired(
            Some(localSynchronizerNode),
            config.domainMigrationId,
          )
        } else {
          logger.info(
            "Skipping reconcile sequencer config step because skipSynchronizerInitialization is enabled"
          )
          Future.unit
        }
    } yield (
      decentralizedSynchronizer,
      dsoPartyHosting,
      svStore,
      svAutomation,
      dsoStore,
      dsoAutomation,
    )
  }

  private def checkIsOnboardedAndInDecentralizedNamespace(
      dsoStore: SvDsoStore
  )(implicit traceContext: TraceContext) =
    retryProvider
      .ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "dso_onboard",
        show"the DsoRules list the SV party ${dsoStore.key.svParty}",
        isOnboardedInDsoRules(dsoStore),
        Future.successful({}),
        logger,
      )
      .flatMap { _ =>
        checkIsInDecentralizedNamespace(
          dsoStore
        )
      }

  private def setupDsoParty(
      domain: SynchronizerId,
      connection: BaseLedgerConnection,
      namespace: Namespace,
  )(implicit
      tc: TraceContext
  ): Future[PartyId] =
    for {
      dso <- connection.ensurePartyAllocated(
        TopologyStoreId.Synchronizer(domain),
        sv1Config.dsoPartyHint,
        Some(namespace),
        participantAdminConnection,
      )
      // this is idempotent
      _ <- connection.grantUserRights(
        config.ledgerApiUser,
        Seq(dso),
        Seq.empty,
      )
    } yield dso

  private def initialTrafficControlParameters: TrafficControlParameters = {
    TrafficControlParameters(
      sv1Config.initialSynchronizerFeesConfig.baseRateBurstAmount,
      sv1Config.initialSynchronizerFeesConfig.readVsWriteScalingFactor,
      // have to convert canton.config.NonNegativeDuration to canton.time.NonNegativeDuration
      PositiveFiniteDuration.tryOfSeconds(
        sv1Config.initialSynchronizerFeesConfig.baseRateBurstWindow.duration.toSeconds
      ),
      freeConfirmationResponses = config.enableFreeConfirmationResponses,
    )
  }

  private def bootstrapDomain(synchronizerNode: LocalSynchronizerNode)(implicit
      tc: TraceContext
  ): Future[(Namespace, SynchronizerId)] = {
    withSpan("bootstrapDomain") { implicit tc => _ =>
      logger.info("Bootstrapping the domain as sv1")

      (
        participantAdminConnection.getParticipantId(),
        synchronizerNode.mediatorAdminConnection.getMediatorId,
        synchronizerNode.sequencerAdminConnection.getSequencerId,
      ).flatMapN { case (participantId, mediatorId, sequencerId) =>
        val namespace =
          DecentralizedNamespaceDefinition.computeNamespace(Set(participantId.uid.namespace))
        val synchronizerId = SynchronizerId(
          UniqueIdentifier.tryCreate(
            "global-domain",
            namespace,
          )
        )
        val initialValues = DynamicSynchronizerParameters.initialValues(ProtocolVersion.v34)
        val values = initialValues.tryUpdate(
          trafficControlParameters = Some(initialTrafficControlParameters),
          reconciliationInterval =
            PositiveSeconds.fromConfig(sv1Config.acsCommitmentReconciliationInterval),
          acsCommitmentsCatchUp = Some(SvUtil.defaultAcsCommitmentsCatchUpParameters),
          preparationTimeRecordTimeTolerance =
            NonNegativeFiniteDuration.fromConfig(config.preparationTimeRecordTimeTolerance),
          mediatorDeduplicationTimeout =
            NonNegativeFiniteDuration.fromConfig(config.mediatorDeduplicationTimeout),
        )
        for {
          physicalSynchronizerId <- retryProvider.ensureThatO(
            RetryFor.WaitingOnInitDependency,
            "init_sequencer",
            "sequencer is initialized",
            synchronizerNode.sequencerAdminConnection.getStatus
              .map(_.successOption.map(_.synchronizerId)),
            for {
              // must be done before the other topology transaction as the decentralize namespace is used for authorization
              decentralizedNamespace <- participantAdminConnection
                .proposeInitialDecentralizedNamespaceDefinition(
                  namespace,
                  NonEmpty.mk(Set, participantId.uid.namespace),
                  threshold = PositiveInt.one,
                )
              (
                identityTransactions,
                synchronizerParametersState,
                sequencerState,
                mediatorState,
              ) <- (
                MonadUtil
                  .sequentialTraverse(
                    List(
                      participantAdminConnection,
                      synchronizerNode.mediatorAdminConnection,
                      synchronizerNode.sequencerAdminConnection,
                    )
                  ) { con =>
                    con
                      .getId()
                      .flatMap(con.getIdentityTransactions(_, TopologyStoreId.Authorized))
                  }
                  .map(_.flatten),
                participantAdminConnection.proposeInitialDomainParameters(
                  synchronizerId,
                  values,
                ),
                participantAdminConnection.proposeInitialSequencerSynchronizerState(
                  synchronizerId,
                  active = Seq(sequencerId),
                  observers = Seq.empty,
                ),
                participantAdminConnection.proposeInitialMediatorSynchronizerState(
                  synchronizerId,
                  group = NonNegativeInt.zero,
                  active = Seq(mediatorId),
                  observers = Seq.empty,
                ),
              ).tupled
              bootstrapTransactions =
                (Seq(
                  decentralizedNamespace,
                  synchronizerParametersState,
                  sequencerState,
                  mediatorState,
                ) ++ identityTransactions).sorted
                  .mapFilter(_.selectOp[TopologyChangeOp.Replace])
                  .map(signed =>
                    StoredTopologyTransaction(
                      SequencedTime(CantonTimestamp.MinValue.immediateSuccessor),
                      EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor),
                      None,
                      signed.copy(isProposal = false),
                      None,
                    )
                  )
              _ <- synchronizerNode.sequencerAdminConnection.initializeFromBeginning(
                StoredTopologyTransactions(bootstrapTransactions),
                synchronizerNode.staticDomainParameters,
              )
            } yield (),
            logger,
          )
          _ <- retryProvider.ensureThatB(
            RetryFor.WaitingOnInitDependency,
            "init_mediator",
            "mediator is initialized",
            synchronizerNode.mediatorAdminConnection.getStatus.map(_.successOption.isDefined),
            synchronizerNode.mediatorAdminConnection.initialize(
              physicalSynchronizerId,
              synchronizerNode.sequencerConnection,
              synchronizerNode.mediatorSequencerAmplification,
            ),
            logger,
          )
        } yield (namespace, synchronizerId)
      }
    }
  }

  private def requiredDars(initialPackageConfig: InitialPackageConfig): Seq[UploadablePackage] = {
    def darsUpToInitialConfig(packageResource: PackageResource, requiredVersion: String) = {
      packageResource.all
        .filter { darResource =>
          val required = PackageVersion.assertFromString(requiredVersion)
          darResource.metadata.version == required || !config.latestPackagesOnly && darResource.metadata.version < required
        }
        .map(UploadablePackage.fromResource)
    }

    Seq(
      DarResources.amulet -> initialPackageConfig.amuletVersion,
      DarResources.dsoGovernance -> initialPackageConfig.dsoGovernanceVersion,
      DarResources.validatorLifecycle -> initialPackageConfig.validatorLifecycleVersion,
    ).flatMap { case (packageResource, requiredVersion) =>
      darsUpToInitialConfig(packageResource, requiredVersion)
    }
  }

  /** A private class to share the dsoStoreWithIngestion and the global domain-id
    * across setup methods.
    */
  private class WithDsoStore(
      dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
      synchronizerId: SynchronizerId,
  ) {

    private val dsoStore = dsoStoreWithIngestion.store
    private val dsoParty = dsoStore.key.dsoParty
    private val svParty = dsoStore.key.svParty
    private val synchronizerNodeReconciler = new SynchronizerNodeReconciler(
      dsoStore,
      dsoStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Low),
      config.legacyMigrationId,
      clock = clock,
      retryProvider = retryProvider,
      logger = logger,
    )

    /** The one and only entry-point: found a fresh DSO, given a properly
      * allocated DSO party
      */
    def foundDso(initialRound: Long, packageVersionSupport: PackageVersionSupport)(implicit
        tc: TraceContext
    ): Future[Unit] = retryProvider.retry(
      RetryFor.WaitingOnInitDependency,
      "bootstrap_dso",
      "bootstrapping DSO",
      bootstrapDso(initialRound, packageVersionSupport),
      logger,
    )

    def reconcileSequencerConfigIfRequired(
        localSynchronizerNode: Option[LocalSynchronizerNode],
        migrationId: Long,
    )(implicit
        tc: TraceContext
    ): Future[Unit] = {
      synchronizerNodeReconciler.reconcileSynchronizerNodeConfigIfRequired(
        localSynchronizerNode,
        synchronizerId,
        SynchronizerNodeState.OnboardedImmediately,
        migrationId,
        config.scan,
      )
    }

    // Create DsoRules and AmuletRules and open the first mining round
    private def bootstrapDso(initialRound: Long, packageVersionSupport: PackageVersionSupport)(
        implicit tc: TraceContext
    ): Future[Unit] = {
      val dsoRulesConfig = SvUtil.defaultDsoRulesConfig(
        synchronizerId,
        sv1Config.voteCooldownTime,
        sv1Config.acsCommitmentReconciliationInterval,
      )
      for {
        (participantId, trafficStateForAllMembers, amuletRules, dsoRules) <- (
          participantAdminConnection.getParticipantId(),
          localSynchronizerNode.sequencerAdminConnection.listSequencerTrafficControlState(),
          dsoStore.lookupAmuletRules(),
          dsoStore.lookupDsoRulesWithStateWithOffset(),
        ).tupled
        _ <- dsoRules match {
          case QueryResult(offset, None) =>
            amuletRules match {
              case Some(amuletRules) =>
                sys.error(
                  "A AmuletRules contract was found but no DsoRules contract exists. " +
                    show"This should never happen.\nAmuletRules: $amuletRules"
                )
              case None =>
                for {
                  developmentFund <- packageVersionSupport.supportDevelopmentFund(
                    Seq(svParty),
                    clock.now,
                  )
                  amuletConfig = defaultAmuletConfig(
                    sv1Config.initialTickDuration,
                    sv1Config.initialMaxNumInputs,
                    synchronizerId,
                    sv1Config.initialSynchronizerFeesConfig.extraTrafficPrice.value,
                    sv1Config.initialSynchronizerFeesConfig.minTopupAmount.value,
                    sv1Config.initialSynchronizerFeesConfig.baseRateBurstAmount.value,
                    sv1Config.initialSynchronizerFeesConfig.baseRateBurstWindow,
                    sv1Config.initialSynchronizerFeesConfig.readVsWriteScalingFactor.value,
                    sv1Config.initialPackageConfig.toPackageConfig,
                    sv1Config.initialHoldingFee,
                    sv1Config.zeroTransferFees,
                    sv1Config.initialTransferPreapprovalFee,
                    sv1Config.initialFeaturedAppActivityMarkerAmount,
                    developmentFundPercentage =
                      if (developmentFund.supported) sv1Config.developmentFundPercentage else None,
                    developmentFundManager = sv1Config.developmentFundManager,
                  )
                  sv1SynchronizerNodes <- SvUtil.getSV1SynchronizerNodeConfig(
                    cometBftNode,
                    localSynchronizerNode,
                    config.scan,
                    synchronizerId,
                    clock,
                    config.domainMigrationId,
                  )
                  _ = logger
                    .info(
                      s"Bootstrapping DSO as $dsoParty and BFT nodes $sv1SynchronizerNodes at round $initialRound"
                    )
                  bootstrapWithNonZeroRound <- packageVersionSupport
                    .supportBootstrapWithNonZeroRound(
                      Seq(svParty),
                      clock.now,
                    )
                  _ <- dsoStoreWithIngestion
                    .connection(SpliceLedgerConnectionPriority.Low)
                    .submit(
                      actAs = Seq(dsoParty),
                      readAs = Seq.empty,
                      new splice.dsobootstrap.DsoBootstrap(
                        dsoParty.toProtoPrimitive,
                        svParty.toProtoPrimitive,
                        sv1Config.name,
                        sv1Config.firstSvRewardWeightBps,
                        participantId.toProtoPrimitive,
                        sv1SynchronizerNodes,
                        new RelTime(
                          TimeUnit.NANOSECONDS.toMicros(
                            sv1Config.roundZeroDuration
                              .getOrElse(sv1Config.initialTickDuration)
                              .duration
                              .toNanos
                          )
                        ),
                        amuletConfig,
                        sv1Config.initialAmuletPrice.bigDecimal,
                        defaultAnsConfig(
                          sv1Config.initialAnsConfig.renewalDuration,
                          sv1Config.initialAnsConfig.entryLifetime,
                          sv1Config.initialAnsConfig.entryFee,
                        ),
                        dsoRulesConfig,
                        trafficStateForAllMembers
                          .map(m =>
                            m.member.toProtoPrimitive -> new splice.dsorules.TrafficState(
                              m.extraTrafficConsumed.value
                            )
                          )
                          .toMap
                          .asJava,
                        sv1Config.isDevNet,
                        Option
                          .when(bootstrapWithNonZeroRound.supported)(initialRound: java.lang.Long)
                          .toJava,
                      ).createAnd.exerciseDsoBootstrap_Bootstrap,
                    )
                    .withDedup(
                      commandId = SpliceLedgerConnection
                        .CommandId(
                          "org.lfdecentralizedtrust.splice.dso.executeDsoBootstrap",
                          Seq(),
                        ),
                      deduplicationOffset = offset,
                    )
                    .withSynchronizerId(synchronizerId)
                    .yieldUnit()
                } yield ()
            }
          case QueryResult(_, Some(ContractWithState(dsoRules, _))) =>
            amuletRules match {
              case Some(amuletRules) =>
                if (dsoRules.payload.svs.keySet.contains(svParty.toProtoPrimitive)) {
                  logger.info(
                    "AmuletRules and DsoRules already exist and sv1 party is an SV; doing nothing." +
                      show"\nAmuletRules: $amuletRules\nDsoRules: $dsoRules"
                  )
                  Future.successful(())
                } else {
                  sys.error(
                    "AmuletRules and DsoRules already exist but party tasked with creating the DSO isn't an sv." +
                      "Is more than one SV app configured to `found-dso`?" +
                      show"\nAmuletRules: $amuletRules\nDsoRules: $dsoRules"
                  )
                }
              case None =>
                sys.error(
                  "An DsoRules contract was found but no AmuletRules contract exists. " +
                    show"This should never happen.\nDsoRules: $dsoRules"
                )
            }
        }
      } yield ()
    }

  }

}

object SV1Initializer {

  /** Same ordering as https://github.com/DACH-NY/canton/blob/2fc1a37d815623cb68dcb4b75bc33a498065990e/enterprise/app-base/src/main/scala/com/digitalasset/canton/console/EnterpriseConsoleMacros.scala#L160
    */
  implicit val bootstrapTransactionOrdering
      : Ordering[SignedTopologyTransaction[TopologyChangeOp, TopologyMapping]] =
    (x, y) => {
      def toOrdinal(t: SignedTopologyTransaction[TopologyChangeOp, TopologyMapping]) = {
        t.transaction.mapping.code match {
          case Code.NamespaceDelegation => 1
          case Code.OwnerToKeyMapping => 2
          case Code.DecentralizedNamespaceDefinition => 3
          case _ => 4
        }
      }

      Ordering.Int.compare(
        toOrdinal(x),
        toOrdinal(y),
      )
    }

}
