// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import cats.data.OptionT
import cats.implicits.catsSyntaxOptionId
import cats.syntax.apply.*
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, NonNegativeLong, PositiveInt}
import com.digitalasset.canton.config.SynchronizerTimeTrackerConfig
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{
  ParticipantId,
  PartyId,
  SynchronizerId,
  TopologyManagerError,
}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermission}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.base.error.utils.ErrorDetails
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.SvOnboardingConfirmed
import org.lfdecentralizedtrust.splice.config.{SpliceInstanceNamesConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  TopologySnapshot,
  TopologyTransactionType,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.store.{AppStoreWithIngestion, DomainTimeSynchronization}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.{LocalSynchronizerNode, SvApp}
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvConnection
import org.lfdecentralizedtrust.splice.sv.automation.{SvDsoAutomationService, SvSvAutomationService}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.{
  ReconcileSequencerLimitWithMemberTrafficTrigger,
  SvPackageVettingTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SvOnboardingUnlimitedTrafficTrigger
import org.lfdecentralizedtrust.splice.sv.config.{
  SvAppBackendConfig,
  SvCantonIdentifierConfig,
  SvOnboardingConfig,
}
import org.lfdecentralizedtrust.splice.sv.onboarding.*
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler.SynchronizerNodeState.{
  OnboardedAfterDelay,
  Onboarding,
}
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.util.{SvOnboardingToken, SvUtil}
import org.lfdecentralizedtrust.splice.util.{Contract, PackageVetting, TemplateJsonDecoder}

import java.security.interfaces.ECPrivateKey
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

/** Container for the methods required by the SvApp to initialize a joining SV node. */
class JoiningNodeInitializer(
    synchronizerNodeService: SynchronizerNodeService[LocalSynchronizerNode],
    joiningConfig: Option[SvOnboardingConfig.JoinWithKey],
    participantId: ParticipantId,
    override protected val config: SvAppBackendConfig,
    upgradesConfig: UpgradesConfig,
    override protected val ledgerClient: SpliceLedgerClient,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val clock: Clock,
    override protected val domainTimeSync: DomainTimeSynchronization,
    override protected val storage: DbStorage,
    override val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    override protected val spliceInstanceNamesConfig: SpliceInstanceNamesConfig,
    override protected val grpcClientMetrics: GrpcClientMetrics,
    svAcsStoreDescriptorUserVersion: Option[Long],
    dsoAcsStoreDescriptorUserVersion: Option[Long],
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
    mat: Materializer,
    tc: TraceContext,
    tracer: Tracer,
    esf: ExecutionSequencerFactory,
    actorSystem: ActorSystem,
) extends NodeInitializerUtil {

  private lazy val svConnection: Future[(SvOnboardingConfig.JoinWithKey, SvConnection)] =
    OptionT(joiningConfig.traverse { conf =>
      SvConnection(conf.svClient.adminApi, upgradesConfig, retryProvider, loggerFactory).map {
        connection =>
          (conf, connection)
      }
    }).getOrElse(
      sys.error(
        "An onboarding config is required."
      )
    )

  private def migrationIdFromSponsorSv(): Future[Long] =
    svConnection.flatMap { case (_, connection) =>
      retryProvider.getValueWithRetries(
        RetryFor.WaitingOnInitDependency,
        "get_migration_id",
        "Getting migration id from sponsor SV",
        connection.getMigrationId(),
        logger,
      )
    }

  def joinDsoAndOnboardNodes(): Future[
    (
        SynchronizerId,
        DsoPartyHosting,
        SvSvStore,
        SvSvAutomationService,
        SvDsoStore,
        SvDsoAutomationService,
    )
  ] = {
    val initConnection = ledgerClient.readOnlyConnection(
      this.getClass.getSimpleName,
      loggerFactory,
    )
    // We need to connect to the domain here because otherwise we create a circular dependency
    // with the validator app: The validator app waits for its user to be provisioned (which happens in createValidatorUser)
    // before establishing a domain connection, but allocating the SV party requires a domain connection.
    val domainConfigO = config.domains.global.url.map(url =>
      SynchronizerConnectionConfig(
        config.domains.global.alias,
        SequencerConnections.tryMany(
          Seq(
            GrpcSequencerConnection
              .create(url)
              .fold(
                error =>
                  throw Status.INVALID_ARGUMENT
                    .withDescription(s"Invalid synchronizer url $url: $error")
                    .asRuntimeException(),
                identity,
              )
          ),
          PositiveInt.one,
          // We only have a single connection here.
          sequencerLivenessMargin = NonNegativeInt.zero,
          config.participantClient.sequencerRequestAmplification.toInternal,
          sequencerConnectionPoolDelays =
            config.participantClient.sequencerConnectionPoolDelays.toInternal,
          subscriptionLivenessLimits =
            config.participantClient.subscriptionLivenessLimits.toInternal,
        ),
        // Set manualConnect = true to avoid any issues with interrupted SV onboardings.
        // This is changed to false after SV onboarding completes.
        manualConnect = true,
        timeTracker = SynchronizerTimeTrackerConfig(
          minObservationDuration = config.timeTrackerMinObservationDuration,
          observationLatency = config.timeTrackerObservationLatency,
        ),
      )
    )
    for {
      (dsoPartyId, registeredGlobalSync) <- (
        // If we're not onboarded yet, this waits for the sponsoring SV
        getDsoPartyId(initConnection),
        // Register domain with manualConnect=true. Confusingly, this still connects the first time.
        // However, it won't connect if we crash and get here again which is what we're really after.
        // If the url is unset, we skip this step. This is fine if the node has already initialized its
        // own sequencer.
        domainConfigO.traverse(
          participantAdminConnection.ensureSynchronizerRegisteredWithManualConnect(
            _,
            RetryFor.WaitingOnInitDependency,
          )
        ),
      ).tupled
      psid <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "getPhysicalSynchronizerId",
        "Get the physical synchronizer id in joinDsoAndOnboardNodes",
        participantAdminConnection
          .getPhysicalSynchronizerId(config.domains.global.alias),
        logger,
      )
      decentralizedSynchronizerId = psid.logical
      dsoPartyHosting = newDsoPartyHosting(dsoPartyId)
      dsoPartyIsAuthorized <- dsoPartyHosting.isDsoPartyAuthorizedOn(
        decentralizedSynchronizerId,
        registeredGlobalSync,
        participantId,
      )
      _ <-
        // do not reconnect if we host the party, as we can be in some LSU stage and the participant cannot reconnect if the new sync is not functional
        if (!dsoPartyIsAuthorized) {
          reconnectSynchronizersIfDsoPartyMigrationSafe(
            decentralizedSynchronizerId,
            dsoPartyId,
            tolerateUninitializedStore = registeredGlobalSync.exists(_.config.manualConnect),
          )
        } else Future.unit
      svParty <- SetupUtil.setupSvParty(
        initConnection,
        config,
        participantAdminConnection,
      )
      storeKey = SvStore.Key(svParty, dsoPartyId)
      // We need to vet early so the packages are uploaded when we try to use template
      // filters in the ACS queries in the store.
      _ <- joiningConfig.traverse_ { conf =>
        if (!dsoPartyIsAuthorized) {
          // If the DSO party has already been authorized we should be far enough to not need this step and deliberately avoid it
          // to make sure we don't introduce a dependency on the sponsoring SV.
          vetThroughSponsor(conf)
        } else Future.unit
      }
      domainMigrationId <- resolveDomainMigrationId(migrationIdFromSponsorSv())
      svStore = newSvStore(
        storeKey,
        domainMigrationId,
        participantId,
        svAcsStoreDescriptorUserVersion,
      )
      dsoStore = newDsoStore(
        svStore.key,
        domainMigrationId,
        participantId,
        dsoAcsStoreDescriptorUserVersion,
      )
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        decentralizedSynchronizerId,
        initConnection,
        loggerFactory,
      )
      svAutomation = newSvSvAutomationService(
        svStore,
        dsoStore,
        ledgerClient,
        participantAdminConnection,
        synchronizerNodeService,
        packageVersionSupport,
      )
      connection = svAutomation.connection(SpliceLedgerConnectionPriority.Low)
      _ <- joiningConfig.fold(Future.unit)(onboardingConfig =>
        SetupUtil.ensureSvNameMetadataAnnotation(
          connection,
          config,
          onboardingConfig.name,
        )
      )
      currentNode <- synchronizerNodeService.activeSynchronizerNode()
      // We need to first wait to ensure the CometBFT node is caught up
      // If the CometBFT node is not caught up and we start the CometBFT triggers, if the network doesn't have any
      // fault tolerance then it might be blocked until the CometBFT node is caught up.
      _ <- waitUntilCometBftNodeHasCaughtUp(currentNode)
      withSvStore = new WithSvStore(
        svAutomation,
        new JoiningNodeDsoPartyHosting(
          participantAdminConnection,
          joiningConfig,
          upgradesConfig,
          dsoPartyId,
          dsoPartyHosting,
          retryProvider,
          loggerFactory,
        ),
        decentralizedSynchronizerId,
      )
      dsoAutomation <-
        if (dsoPartyIsAuthorized) {
          logger.info("DSO party is authorized to our participant.")
          for {
            _ <- SetupUtil.grantSvUserRightActAsDso(
              connection,
              config.ledgerApiUser,
              svStore.key.dsoParty,
            )
            synchronizerNodeReconciler = new SynchronizerNodeReconciler(
              dsoStore,
              connection,
              clock,
              retryProvider,
              loggerFactory,
              config.scan,
            )
            dsoAutomation <-
              newSvDsoAutomationService(
                svStore,
                dsoStore,
                synchronizerNodeService,
                upgradesConfig,
                packageVersionSupport,
                decentralizedSynchronizerId,
                config.parameters.enabledFeatures,
                synchronizerNodeReconciler,
              )
            // register before maybe reconnecting to proceed with the LSU if the participant is in a broken state
            _ = dsoAutomation.registerLsuTriggers()
            _ <- reconnectSynchronizersIfDsoPartyMigrationSafe(
              decentralizedSynchronizerId,
              dsoPartyId,
              tolerateUninitializedStore = false,
            )
            _ <- svStore.domains.waitForDomainConnection(config.domains.global.alias)
            _ <- dsoStore.domains.waitForDomainConnection(config.domains.global.alias)
            _ <- retryProvider
              .ensureThatB(
                RetryFor.WaitingOnInitDependency,
                "dso_onboard",
                show"the DsoRules list the SV party ${dsoStore.key.svParty}",
                isOnboardedInDsoRules(dsoStore), {
                  for {
                    (joiningConfig, svConnection) <- svConnection
                    _ <- withSvStore.startOnboardingWithDsoPartyHosted(
                      dsoAutomation,
                      svConnection,
                      joiningConfig,
                    )
                  } yield ()
                },
                logger,
              )
          } yield dsoAutomation
        } else {
          logger.info(
            "The DSO party is not authorized to our participant. " +
              "Starting onboarding with DSO party migration."
          )
          for {
            (joiningConfig, svConnection) <- svConnection
            dsoAutomation <- withSvStore
              .startOnboardingWithDsoPartyMigration(
                initConnection,
                dsoStore,
                svConnection,
                joiningConfig,
                packageVersionSupport,
                decentralizedSynchronizerId,
              )
            _ = dsoAutomation.registerLsuTriggers()
          } yield dsoAutomation
        }
      // We set the initial round to the one from the sponsor if no initial round is store in the user metadata yet
      // This is needed so that all scans can aggregate and backfill using the same initial round
      // Note: we accept the risk that sponsors could maliciously set a wrong initialRound as this is dev/testnet only.
      _ <- establishInitialRound(
        connection,
        upgradesConfig,
      )
      _ <- ensureCometBftGovernanceKeysAreSet(
        currentNode.cometbftNode,
        svParty,
        dsoStore,
        dsoAutomation,
      )
      // Set autoConnect=true now that DSO party migration is complete
      _ <- participantAdminConnection.modifySynchronizerConnectionConfig(
        config.domains.global.alias,
        Some(psid),
        config => if (config.manualConnect) Some(config.copy(manualConnect = false)) else None,
      )
      cantonIdentifierConfig = config.cantonIdentifierConfig.getOrElse(
        SvCantonIdentifierConfig.default(config)
      )
      _ <-
        if (!config.shouldSkipSynchronizerInitialization) {
          SynchronizerNodeInitializer.initializeLocalCantonNodesWithNewIdentities(
            cantonIdentifierConfig,
            currentNode,
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
      _ <- ensureCantonNodesOTKRotatedIfNeeded(
        config.skipSynchronizerInitialization,
        cantonIdentifierConfig,
        currentNode.some,
        clock,
        loggerFactory,
        retryProvider,
        decentralizedSynchronizerId,
      )
      _ <- onboard(
        decentralizedSynchronizerId,
        dsoAutomation,
        svAutomation,
      )
    } yield {
      (
        decentralizedSynchronizerId,
        dsoPartyHosting,
        svStore,
        svAutomation,
        dsoStore,
        dsoAutomation,
      )
    }
  }

  private def vetThroughSponsor(joiningConfig: SvOnboardingConfig.JoinWithKey): Future[Unit] = {
    logger.info("Vetting packages based on state from sponsor")
    for {
      // This is not a BFT read: That's acceptable because
      // we will only vet packages that have been statically compiled into the app.
      // At most, we can be tricked into vetting a package a bit too early.
      dsoInfo <- getDsoInfoFromSponsor(joiningConfig, upgradesConfig)
      amuletRules = dsoInfo.amuletRules
      synchronizerId = SynchronizerId.tryFromString(
        amuletRules.payload.configSchedule.initialValue.decentralizedSynchronizer.activeSynchronizer
      )
      vetting = new PackageVetting(
        SvPackageVettingTrigger.packages,
        clock,
        participantAdminConnection,
        loggerFactory,
        config.latestPackagesOnly,
        config.parameters.enabledFeatures.enableUnsupportedDarsUnvetting,
      )
      _ <- vetting.vetCurrentPackages(
        synchronizerId,
        amuletRules.contract,
        config.additionalPackagesToUnvet,
      )
      _ = logger.info("Packages vetting completed")
    } yield ()
  }

  // Note: This is also used for synchronizer migrations
  def onboard(
      decentralizedSynchronizer: SynchronizerId,
      dsoAutomationService: SvDsoAutomationService,
      svSvAutomationService: SvSvAutomationService,
      skipTrafficReconciliationTriggers: Boolean = false,
  ): Future[Unit] = {
    val dsoStore = dsoAutomationService.store
    val dsoPartyId = dsoStore.key.dsoParty
    val synchronizerNodeReconciler = dsoAutomationService.synchronizerNodeReconciler
    for {
      // Do this at the very start as scan depends on it to start up.
      _ <- SetupUtil.ensureDsoPartyMetadataAnnotation(
        svSvAutomationService.connection(SpliceLedgerConnectionPriority.Low),
        config,
        dsoPartyId,
      )
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependencyLong,
        "dso_rules_visible",
        show"the DsoRules and AmuletRules are visible",
        dsoStore.getDsoRules().map(_ => ()),
        logger,
      )
      // Register triggers once the DsoRules are visible and have been ingested
      _ = dsoAutomationService.registerPostOnboardingTriggers()
      // During LSUs this can fail with 'NOT_FOUND: No synchronizer registered and handshaked for Synchronizer'
      // If the call happens while the participant is reconnecting.
      participantReportedPSid <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "getPhysicalSynchronizerId",
        "Get the physical synchronizer id in onboard",
        participantAdminConnection
          .getPhysicalSynchronizerId(config.domains.global.alias),
        logger,
      )
      currentNode <- synchronizerNodeService.activeSynchronizerNode()
      // It is important to wait only here since at this point we may have been added
      // to the decentralized namespace so we depend on our own automation promoting us to
      // not being onboarding.
      _ <- (
        waitForSvParticipantToBeOnboarded(dsoPartyId, decentralizedSynchronizer),
        waitForDsoSvRole(dsoStore),
        waitUntilCometBftNodeIsValidator(currentNode),
      ).tupled
      _ <-
        if (!config.shouldSkipSynchronizerInitialization) {
          for {
            // First, make sure the identity of the new domain nodes is known on the domain
            _ <-
              (
                currentNode.addLocalSequencerIdentityIfRequired(
                  config.domains.global.alias,
                  decentralizedSynchronizer,
                ),
                currentNode.addLocalMediatorIdentityIfRequired(
                  decentralizedSynchronizer
                ),
              ).tupled
            // Finally, fully onboard the sequencer and mediator
            physicalSynchronizerId <-
              currentNode.onboardLocalSequencerIfRequired(
                svConnection.map(_._2),
                // Add the new local domain node to the DSO rules with an "onboarding" status
                // This triggers automation in other SV apps, that's why we make sure the sequencer is known first
                preInit = () =>
                  synchronizerNodeReconciler.reconcileSynchronizerNodeConfigIfRequired(
                    synchronizerNodeService.nodes,
                    decentralizedSynchronizer,
                    Onboarding(participantReportedPSid.serial),
                  ),
              )
            // For domain migrations, the traffic triggers have already been registered earlier and so we skip that step here.
            _ = if (!skipTrafficReconciliationTriggers)
              dsoAutomationService.registerTrafficReconciliationTriggers()
            _ <- currentNode.initializeLocalMediatorIfRequired(
              physicalSynchronizerId
            )
            _ = checkTrafficReconciliationTriggersRegistered(dsoAutomationService)
            _ <- waitForSvToObtainUnlimitedTraffic(
              currentNode,
              decentralizedSynchronizer,
            )
          } yield ()
        } else {
          if (!skipTrafficReconciliationTriggers) {
            dsoAutomationService.registerTrafficReconciliationTriggers()
          }
          checkTrafficReconciliationTriggersRegistered(dsoAutomationService)
          logger.info(
            "Skipping synchronizer initialization because skipSynchronizerInitialization is enabled"
          )
          Future.unit
        }
      _ = dsoAutomationService.registerPostUnlimitedTrafficTriggers()
      _ <-
        if (!config.shouldSkipSynchronizerInitialization) {
          synchronizerNodeReconciler
            .reconcileSynchronizerNodeConfigIfRequired(
              synchronizerNodeService.nodes,
              decentralizedSynchronizer,
              OnboardedAfterDelay,
            )
        } else {
          logger.info(
            "Skipping synchronizer config reconciliation as skipSynchronizerInitialization is enabled"
          )
          Future.unit
        }
      _ <- checkIsInDecentralizedNamespace(dsoStore)
    } yield {
      ()
    }
  }

  // We can only reconnect the domains if the participant:
  // - already hosts the dsoParty or
  // - is not in the process to host it
  // if not we risk reconnecting while the party was authorized but the acs was not imported yet thus breaking the participant
  private def reconnectSynchronizersIfDsoPartyMigrationSafe(
      decentralizedSynchronizerId: SynchronizerId,
      dsoParty: PartyId,
      tolerateUninitializedStore: Boolean,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    // When the synchronizer is registered with manualConnect = true, the participant may not yet
    // have initialized its topology store for that synchronizer. In that case listing the
    // party-to-participant mappings fails with a TOPOLOGY_STORE_NOT_INITIALIZED / TOPOLOGY_STORE_UNKNOWN
    // error, which we treat as an empty list instead of failing.
    def recoverEmptyIfStoreNotInitialized[T]: PartialFunction[Throwable, Seq[T]] = {
      case ex: StatusRuntimeException
          if tolerateUninitializedStore &&
            (ErrorDetails.matches(ex, TopologyManagerError.TopologyStoreNotInitialized) ||
              ErrorDetails.matches(ex, TopologyManagerError.TopologyStoreUnknown)) =>
        logger.info(
          s"Topology store for $decentralizedSynchronizerId is not yet initialized, " +
            "treating the party-to-participant mappings as empty."
        )
        Seq.empty
    }
    retryProvider.retry(
      RetryFor.ClientCalls,
      "reconnect_all_domains",
      "Reconnecting to all domains if participant hosts or is not in the process to host the dsoParty.",
      for {
        participantId <- participantAdminConnection.getParticipantId()
        // Check if the participant hosts the DSO party. If so,
        // the dsoParty is hosted on the participant we can proceed to all domains reconnect
        dsoPartyToParticipantMapping <- participantAdminConnection
          .listPartyToParticipant(
            store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId).some,
            filterParty = dsoParty.filterString,
            filterParticipant = participantId.filterString,
            topologyTransactionType = TopologyTransactionType.AuthorizedState,
          )
          .recover(recoverEmptyIfStoreNotInitialized)
        // Check if he participant has a proposal for hosting the DSO party. If so,
        // we are in the middle of an DSO party migration so don't reconnect to the domain.
        activeDsoPartyToParticipantProposals <- participantAdminConnection
          .listPartyToParticipant(
            store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId).some,
            filterParty = dsoParty.filterString,
            filterParticipant = participantId.filterString,
            topologyTransactionType = TopologyTransactionType.AllProposals,
          )
          .recover(recoverEmptyIfStoreNotInitialized)
        _ <-
          if (
            dsoPartyToParticipantMapping.nonEmpty || activeDsoPartyToParticipantProposals.isEmpty
          ) {
            logger.info("Reconnecting all domains.")
            participantAdminConnection.reconnectAllSynchronizers()
          } else {
            Future.unit
          }
      } yield {
        logger.info(
          s"Participant hosts dsoParty: ${dsoPartyToParticipantMapping.nonEmpty} and has proposals to host dsoParty ${activeDsoPartyToParticipantProposals.nonEmpty}"
        )
      },
      logger,
    )
  }

  private def waitForSvParticipantToBeOnboarded(
      dsoParty: PartyId,
      synchronizerId: SynchronizerId,
  ) = {
    val description =
      show"SV participant $participantId has Submission rights and is not onboarding for party $dsoParty"
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      "submission_rights",
      description,
      for {
        // We do actually want to be able to submit after this so wait until the effective time.
        dsoPartyHosting <- participantAdminConnection
          .getPartyToParticipant(
            synchronizerId,
            dsoParty,
            topologySnapshot = TopologySnapshot.Effective,
          )
      } yield {
        dsoPartyHosting.mapping.participants.find(_.participantId == participantId) match {
          case None =>
            throw Status.NOT_FOUND
              .withDescription(
                show"Party $dsoParty is not hosted on participant $participantId"
              )
              .asRuntimeException()
          case Some(HostingParticipant(_, permission, onboarding)) =>
            if (permission == ParticipantPermission.Submission && !onboarding)
              dsoPartyHosting
            else
              throw Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException()
        }
      },
      logger,
    )
  }

  private def checkTrafficReconciliationTriggersRegistered(
      service: SvDsoAutomationService
  ): Unit = {
    // throws a RuntimeException if the trigger is not registered
    service.trigger[SvOnboardingUnlimitedTrafficTrigger]: Unit
    assert(service.triggers[ReconcileSequencerLimitWithMemberTrafficTrigger].nonEmpty)
  }

  private def waitForSvToObtainUnlimitedTraffic(
      localSynchronizerNode: LocalSynchronizerNode,
      synchronizerId: SynchronizerId,
  ) = {
    val description = "SV nodes have been granted unlimited traffic"
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      "unlimited_traffic",
      description,
      for {
        mediatorId <- localSynchronizerNode.mediatorAdminConnection.getMediatorId
        participantTrafficState <- participantAdminConnection.getParticipantTrafficState(
          synchronizerId
        )
        mediatorTrafficState <- localSynchronizerNode.sequencerAdminConnection
          .getSequencerTrafficControlState(mediatorId)
      } yield {
        val unlimitedTraffic = NonNegativeLong.maxValue
        if (participantTrafficState.extraTrafficPurchased != unlimitedTraffic)
          throw Status.FAILED_PRECONDITION
            .withDescription(
              show"SV participant $participantId does not have unlimited traffic on synchronizer $synchronizerId, expected ${unlimitedTraffic} but got ${participantTrafficState.extraTrafficPurchased}"
            )
            .asRuntimeException()
        if (mediatorTrafficState.extraTrafficLimit != unlimitedTraffic)
          throw Status.FAILED_PRECONDITION
            .withDescription(
              show"SV mediator $mediatorId does not have unlimited traffic on synchronizer $synchronizerId, expected ${unlimitedTraffic} but got ${mediatorTrafficState.state.extraTrafficPurchased}"
            )
            .asRuntimeException()
        ()
      },
      logger,
    )
  }

  private def waitUntilCometBftNodeIsValidator(activeNode: LocalSynchronizerNode) = {
    activeNode.cometbftNode
      .map(_.cometBftClient)
      .map(cometBftClient =>
        retryProvider.waitUntil(
          RetryFor.WaitingOnInitDependency,
          "cometbft_is_validator",
          "CometBFT node is a validator",
          cometBftClient
            .nodeStatus()
            .map { status =>
              if (status.validatorInfo.votingPower.toDouble == 0) {
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    s"CometBFT node is not a validator; voting power is 0."
                  )
                  .asRuntimeException()
              }
            },
          logger,
        )
      )
      .getOrElse({
        logger.info("No CometBFT node found, so not waiting on CometBFT validator.")
        Future.unit
      })
  }

  private def waitUntilCometBftNodeHasCaughtUp(activeNode: LocalSynchronizerNode) = {
    activeNode.cometbftNode
      .map(_.cometBftClient)
      .map(cometBftClient =>
        retryProvider.waitUntil(
          RetryFor.WaitingOnInitDependency,
          "cometbft_up_to_date",
          "CometBFT node has caught up",
          cometBftClient
            .nodeStatus()
            .map { status =>
              if (status.syncInfo.catchingUp) {
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    s"CometBFT node is still catching up; currently at block ${status.syncInfo.latestBlockHeight}."
                  )
                  .asRuntimeException()
              }
            },
          logger,
        )
      )
      .getOrElse({
        logger.info("No CometBFT node found, so not waiting on CometBFT sync.")
        Future.unit
      })
  }

  /** Private class to share svStore, dsoPartyHosting, and global domain-id
    * across utility methods.
    */
  private class WithSvStore(
      svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
      dsoPartyHosting: JoiningNodeDsoPartyHosting,
      synchronizerId: SynchronizerId,
  ) {

    private val svStore = svStoreWithIngestion.store
    private val svParty = svStore.key.svParty
    private val dsoParty = svStore.key.dsoParty

    def startOnboardingWithDsoPartyHosted(
        dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
        svConnection: SvConnection,
        joiningConfig: SvOnboardingConfig.JoinWithKey,
    ): Future[Unit] = {
      new WithDsoStore(dsoStoreWithIngestion)
        .startOnboardingWithDsoPartyHosted(svConnection, joiningConfig)
    }

    /** A private class to share the dsoStoreWithIngestion across utility methods. */
    private class WithDsoStore(
        dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore]
    ) {
      private val dsoStore: SvDsoStore = dsoStoreWithIngestion.store

      def startOnboardingWithDsoPartyHosted(
          svConnection: SvConnection,
          joiningConfig: SvOnboardingConfig.JoinWithKey,
      ): Future[Unit] = {
        val SvOnboardingConfig.JoinWithKey(name, _, publicKey, privateKey, _) = joiningConfig
        SvUtil.keyPairMatches(publicKey, privateKey) match {
          case Right(privateKey_) =>
            for {
              _ <- requestOnboarding(
                svConnection,
                name,
                participantId,
                publicKey,
                privateKey_,
              )
              _ <- addConfirmedSvToDso()
            } yield ()
          case Left(reason) => sys.error(s"Failed parsing provided keys: $reason")
        }
      }

      private def waitForSvOnboardingConfirmedInDsoStore()
          : Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] =
        waitForSvOnboardingConfirmed(() =>
          dsoStore.lookupSvOnboardingConfirmedByParty(dsoStore.key.svParty)
        )

      def addConfirmedSvToDso(): Future[Unit] = {
        val dsoStore = dsoStoreWithIngestion.store
        for {
          // Wait on the DSO store to make sure that we atomically see either the SvOnboardingConfirmed contract
          // or the DsoRules contract.
          _ <- waitForSvOnboardingConfirmedInDsoStore()
          _ <- retryProvider.retry(
            RetryFor.WaitingOnInitDependency,
            "add_dso_sv",
            "add sv to Dso",
            for {
              (dsoRules, amuletRules, openMiningRounds, svOnboardingConfirmedOpt) <- (
                dsoStore.getDsoRules(),
                dsoStore.getAmuletRules(),
                dsoStore.getOpenMiningRoundTriple(),
                dsoStore.lookupSvOnboardingConfirmedByParty(
                  dsoStore.key.svParty
                ),
              ).tupled
              svIsSv = dsoRules.payload.svs.asScala
                .contains(dsoStore.key.svParty.toProtoPrimitive)
              _ <- svOnboardingConfirmedOpt match {
                case None =>
                  if (svIsSv) {
                    logger.info(s"SV is already part of the DSO")
                    Future.unit
                  } else {
                    val msg =
                      "SV is not part of the DSO but there is also no confirmed onboarding, giving up"
                    logger.error(msg)
                    Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
                  }
                case Some(confirmed) =>
                  if (svIsSv) {
                    logger.info(
                      "SvOnboardingConfirmed exists but SV is already part of the DSO"
                    )
                    Future.unit
                  } else {
                    val cmd = dsoRules.exercise(
                      _.exerciseDsoRules_AddConfirmedSv(
                        dsoStore.key.svParty.toProtoPrimitive,
                        confirmed.contractId,
                        openMiningRounds.oldest.contractId,
                        openMiningRounds.middle.contractId,
                        openMiningRounds.newest.contractId,
                        amuletRules.contractId,
                      )
                    )
                    dsoStoreWithIngestion
                      .connection(SpliceLedgerConnectionPriority.Low)
                      .submit(Seq(dsoStore.key.svParty), Seq(dsoStore.key.dsoParty), cmd)
                      .noDedup
                      .yieldUnit()
                  }
              }
            } yield dsoRules,
            logger,
          )
        } yield ()
      }

    }

    def startOnboardingWithDsoPartyMigration(
        initConnection: BaseLedgerConnection,
        dsoStore: SvDsoStore,
        svConnection: SvConnection,
        joiningConfig: SvOnboardingConfig.JoinWithKey,
        packageVersionSupport: PackageVersionSupport,
        synchronizerId: SynchronizerId,
    ): Future[SvDsoAutomationService] = {
      joiningConfig match {
        case SvOnboardingConfig.JoinWithKey(name, _, publicKey, privateKey, _) =>
          SvUtil.keyPairMatches(publicKey, privateKey) match {
            case Right(privateKey_) =>
              for {
                _ <- svStore.lookupSvOnboardingConfirmed().flatMap {
                  // We're already in the process of onboarding
                  case Some(_) =>
                    Future.unit
                  case None =>
                    for {
                      _ <- svStore.domains.waitForDomainConnection(config.domains.global.alias)
                      _ <- requestOnboarding(
                        svConnection,
                        name,
                        participantId,
                        publicKey,
                        privateKey_,
                      )
                      // Wait on the SV store because the DSO party is not yet onboarded.
                      _ <- waitForSvOnboardingConfirmedInSvStore()
                    } yield ()
                }
                _ <- startHostingDsoPartyInParticipant()
                // We need to wait for the ledger API server to see the party otherwise the
                // grantUserRights call will fail.
                _ <- initConnection.waitForPartyOnLedgerApi(svStore.key.dsoParty)
                _ <- SetupUtil.grantSvUserRightActAsDso(
                  svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Low),
                  config.ledgerApiUser,
                  svStore.key.dsoParty,
                )
                _ = logger.info(s"granted ${config.ledgerApiUser} readAs rights for dsoParty")
                synchronizerNodeReconciler = new SynchronizerNodeReconciler(
                  dsoStore,
                  svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Low),
                  clock,
                  retryProvider,
                  loggerFactory,
                  config.scan,
                )
                dsoAutomation <- newSvDsoAutomationService(
                  svStore,
                  dsoStore,
                  synchronizerNodeService,
                  upgradesConfig,
                  packageVersionSupport,
                  synchronizerId,
                  config.parameters.enabledFeatures,
                  synchronizerNodeReconciler,
                )
                _ <- dsoAutomation.store.domains.waitForDomainConnection(
                  config.domains.global.alias
                )
                withDsoStore = new WithDsoStore(dsoAutomation)
                _ <- withDsoStore.addConfirmedSvToDso()
              } yield dsoAutomation
            case Left(reason) => sys.error(s"Failed parsing provided keys: $reason")
          }
      }
    }

    private def waitForSvOnboardingConfirmedInSvStore()
        : Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] =
      waitForSvOnboardingConfirmed(() => svStore.lookupSvOnboardingConfirmed())

    private def waitForSvOnboardingConfirmed(
        lookupSvOnboardingConfirmed: () => Future[
          Option[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]]
        ]
    ): Future[Contract[SvOnboardingConfirmed.ContractId, SvOnboardingConfirmed]] = {
      val description = show"SvOnboardingConfirmed contract for $svParty"
      retryProvider.getValueWithRetries(
        RetryFor.WaitingOnInitDependency,
        "sv_onboarding_confirmed",
        description,
        for {
          svOnboardingConfirmedOpt <- lookupSvOnboardingConfirmed()
          svOnboardingConfirmed <- svOnboardingConfirmedOpt match {
            case Some(sc) => Future.successful(sc)
            case None =>
              throw Status.NOT_FOUND.withDescription(description).asRuntimeException()
          }
        } yield svOnboardingConfirmed,
        logger,
      )
    }

    private def requestOnboarding(
        svConnection: SvConnection,
        name: String,
        participantId: ParticipantId,
        publicKey: String,
        privateKey: ECPrivateKey,
    ): Future[Unit] = {
      SvOnboardingToken(name, publicKey, svParty, participantId, dsoParty).signAndEncode(
        privateKey
      ) match {
        case Right(token) =>
          logger.info(s"Requesting to be onboarded via the sponsor SV")
          for {
            _ <- retryProvider.retry(
              RetryFor.WaitingOnInitDependency,
              "request_onboarding",
              "request onboarding",
              svConnection.startSvOnboarding(token),
              logger,
            )
          } yield ()
        case Left(error) =>
          Future.failed(
            Status.INTERNAL
              .withDescription(s"Could not create onboarding token: $error")
              .asRuntimeException()
          )
      }
    }

    private def startHostingDsoPartyInParticipant(): Future[Unit] = {
      dsoPartyHosting
        // TODO(DACH-NY/canton-network-node#5364): consider inlining the relevant parts from DsoPartyHosting
        .hostPartyOnOwnParticipant(
          config.domains.global.alias,
          synchronizerId,
          participantId,
          svParty,
        )
        .map(
          _.getOrElse(
            sys.error(s"Failed to host DSO party on participant $participantId")
          )
        )
    }
  }

  private def getDsoPartyId(connection: BaseLedgerConnection): Future[PartyId] = for {
    dsoPartyFromMetadata <- connection.lookupDsoPartyFromUserMetadata(config.ledgerApiUser)
    dsoParty <- dsoPartyFromMetadata
      .fold(
        {
          val conf = joiningConfig
            .getOrElse(
              sys.error(
                "An onboarding config is required to get the DSO party ID from a sponsoring SV; exiting."
              )
            )
          retryProvider.getValueWithRetries(
            RetryFor.WaitingOnInitDependency,
            "dso_party_from_sponsor",
            "DSO party ID from sponsoring SV",
            getDsoPartyIdFromSponsor(conf),
            logger,
          )
        }
      )(Future.successful)
  } yield dsoParty

  private def getDsoPartyIdFromSponsor(
      joiningConfig: SvOnboardingConfig.JoinWithKey
  ): Future[PartyId] =
    getDsoInfoFromSponsor(joiningConfig, upgradesConfig).map(_.dsoParty)

  private def waitForDsoSvRole(dsoStore: SvDsoStore): Future[Unit] = {
    val svParty = dsoStore.key.svParty
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "dso_membership",
      show"DsoRules are visible and list $svParty as an sv",
      for {
        dsoRules <- dsoStore.lookupDsoRules()
        _ <- dsoRules match {
          case Some(c) =>
            if (SvApp.isSvParty(dsoStore.key.svParty, c.contract)) {
              Future.successful(())
            } else {
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  show"DsoRules found but $svParty is not an sv"
                )
                .asRuntimeException()
            }
          case None =>
            throw Status.NOT_FOUND
              .withDescription(show"DsoRules contract not found")
              .asRuntimeException()
        }
      } yield (),
      logger,
    )
  }
}

object JoiningNodeInitializer {}
