// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.admin.api.client.data.SequencerAdminStatus.implicitPrettyString
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{
  EnabledFeaturesConfig,
  NetworkAppClientConfig,
  SpliceInstanceNamesConfig,
  UpgradesConfig,
}
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection.INITIAL_ROUND_USER_METADATA_KEY
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvConnection
import org.lfdecentralizedtrust.splice.sv.automation.{SvDsoAutomationService, SvSvAutomationService}
import org.lfdecentralizedtrust.splice.sv.cometbft.{CometBftNode, CometBftRequestSigner}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.{
  DomainMigration,
  FoundDso,
  JoinWithKey,
}
import org.lfdecentralizedtrust.splice.sv.config.{SvAppBackendConfig, SvCantonIdentifierConfig}
import org.lfdecentralizedtrust.splice.sv.onboarding.domainmigration.DomainMigrationInitializer.loadDomainMigrationDump
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvStore, SvSvStore}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

trait NodeInitializerUtil extends NamedLogging with Spanning with SynchronizerNodeConfigClient {

  protected val config: SvAppBackendConfig
  protected val storage: DbStorage
  protected val retryProvider: RetryProvider
  protected val clock: Clock
  protected val domainTimeSync: DomainTimeSynchronization
  protected val domainUnpausedSync: DomainUnpausedSynchronization
  protected val participantAdminConnection: ParticipantAdminConnection
  protected val cometBftNode: Option[CometBftNode]
  protected val ledgerClient: SpliceLedgerClient
  protected val spliceInstanceNamesConfig: SpliceInstanceNamesConfig

  protected def newSvStore(
      key: SvStore.Key,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
      acsStoreDescriptorUserVersion: Option[Long],
  )(implicit
      ec: ExecutionContext,
      templateDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvSvStore = SvSvStore(
    key,
    storage,
    loggerFactory,
    retryProvider,
    domainMigrationInfo,
    participantId,
    config.automation.ingestion,
    acsStoreDescriptorUserVersion,
  )

  protected def newSvSvAutomationService(
      svStore: SvSvStore,
      dsoStore: SvDsoStore,
      ledgerClient: SpliceLedgerClient,
      participantAdminConnection: ParticipantAdminConnection,
      localSynchronizerNode: Option[LocalSynchronizerNode],
  )(implicit
      ec: ExecutionContextExecutor,
      mat: Materializer,
      tracer: Tracer,
      esf: ExecutionSequencerFactory,
      actorSystem: ActorSystem,
  ) =
    new SvSvAutomationService(
      clock,
      domainTimeSync,
      domainUnpausedSync,
      config,
      svStore,
      dsoStore,
      storage,
      ledgerClient,
      participantAdminConnection,
      localSynchronizerNode,
      retryProvider,
      config.topologySnapshotConfig,
      loggerFactory,
    )

  protected def newDsoStore(
      key: SvStore.Key,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
      acsStoreDescriptorUserVersion: Option[Long],
  )(implicit
      ec: ExecutionContext,
      templateDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvDsoStore = {
    SvDsoStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
      config.automation.ingestion,
      acsStoreDescriptorUserVersion,
    )
  }

  protected def newSvDsoAutomationService(
      svStore: SvSvStore,
      dsoStore: SvDsoStore,
      localSynchronizerNode: Option[LocalSynchronizerNode],
      upgradesConfig: UpgradesConfig,
      packageVersionSupport: PackageVersionSupport,
      enabledFeatures: EnabledFeaturesConfig,
  )(implicit
      ec: ExecutionContextExecutor,
      mat: Materializer,
      tracer: Tracer,
      httpClient: HttpClient,
      templateJsonDecoder: TemplateJsonDecoder,
  ) =
    new SvDsoAutomationService(
      clock,
      domainTimeSync,
      domainUnpausedSync,
      config,
      svStore,
      dsoStore,
      ledgerClient,
      participantAdminConnection,
      retryProvider,
      cometBftNode,
      localSynchronizerNode,
      upgradesConfig,
      spliceInstanceNamesConfig,
      loggerFactory,
      packageVersionSupport,
      enabledFeatures,
    )

  protected def newDsoPartyHosting(
      dsoParty: PartyId
  )(implicit ec: ExecutionContextExecutor) = new DsoPartyHosting(
    participantAdminConnection,
    dsoParty,
    retryProvider,
    loggerFactory,
  )

  protected def rotateGenesisGovernanceKeyForSV1(
      cometBftNode: Option[CometBftNode],
      name: String,
  )(implicit tc: TraceContext): Future[Unit] =
    cometBftNode match {
      case Some(cometBftNode) =>
        cometBftNode.rotateGenesisGovernanceKeyForSV1(name)
      case _ => Future.unit
    }

  protected def ensureCometBftGovernanceKeysAreSet(
      cometBftNode: Option[CometBftNode],
      svParty: PartyId,
      dsoStore: SvDsoStore,
      dsoAutomation: SvDsoAutomationService,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    cometBftNode match {
      case Some(cometBftNode) =>
        for {
          _ <- retryProvider.waitUntil(
            RetryFor.WaitingOnInitDependency,
            "updated_node_config_dso_state",
            "Governance keys are updated in the dso state",
            for {
              (rulesAndState, synchronizerNodeConfig) <- getCometBftNodeConfigDsoState(
                dsoStore,
                svParty,
              ).getOrElse(throw new RuntimeException("No DSO rules with SV node state found"))
              governanceKeysPubKey = synchronizerNodeConfig match {
                case Some(synchronizerNodeConfig) =>
                  synchronizerNodeConfig.cometBft.governanceKeys.asScala.map(_.pubKey).toSeq
                case None => Seq.empty
              }
              genesisKeysPubKey = CometBftRequestSigner.genesisSigner.publicKeyBase64
              governanceKeyNotUpdatedInDsoState = governanceKeysPubKey.contains(
                genesisKeysPubKey
              )
              _ = if (governanceKeyNotUpdatedInDsoState) {
                for {
                  localSvNodeConfig <- cometBftNode.getLocalNodeConfig()
                  newSvNodeConfig = getNewSynchronizerNodeConfig(
                    synchronizerNodeConfig,
                    localSvNodeConfig,
                  )
                  _ <- updateSynchronizerNodeConfig(
                    rulesAndState,
                    newSvNodeConfig,
                    dsoStore,
                    dsoAutomation.connection(SpliceLedgerConnectionPriority.High),
                  )
                } yield ()
              }
            } yield {
              if (governanceKeyNotUpdatedInDsoState)
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    "New governance keys is not in the dso state"
                  )
                  .asRuntimeException()
            },
            logger,
          )
        } yield ()
      case None => Future.unit
    }
  }

  protected def isOnboardedInDsoRules(
      svcStore: SvDsoStore
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] = for {
    dsoRules <- svcStore.lookupDsoRules()
    isInDsoRulesSvs = dsoRules.exists(
      _.payload.svs.keySet.contains(svcStore.key.svParty.toProtoPrimitive)
    )
  } yield isInDsoRulesSvs

  protected def checkIsInDecentralizedNamespace(
      dsoStore: SvDsoStore
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    retryProvider
      .waitUntil(
        RetryFor.WaitingOnInitDependency,
        "dso_onboard_namespace",
        s"the namespace of ${dsoStore.key.svParty} is part of the decentralized namespace",
        isOnboardedInDecentralizedNamespace(dsoStore).map(isOnboarded =>
          if (!isOnboarded) {
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"The SV ${dsoStore.key.svParty} is not yet a member of the decentralized namespace"
              )
              .asRuntimeException
          }
        ),
        logger,
      )

  protected def establishInitialRound(
      connection: BaseLedgerConnection,
      upgradesConfig: UpgradesConfig,
      packageVersionSupport: PackageVersionSupport,
      svParty: PartyId,
  )(implicit
      tc: TraceContext,
      ece: ExecutionContextExecutor,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      mat: Materializer,
  ): Future[Long] = {
    for {
      initialRound <- connection
        // On restarts, use the user's metadata initial round
        // On resets, the initial SV set it to its configuration, followers learn it from their sponsor
        .lookupUserMetadata(config.ledgerApiUser, INITIAL_ROUND_USER_METADATA_KEY)
        .flatMap {
          case Some(round) =>
            logger.info(s"Initial round $round is already set in user's metadata.")
            Future.successful(round.toLong)
          case None =>
            packageVersionSupport
              .supportBootstrapWithNonZeroRound(
                Seq(svParty),
                clock.now,
              )
              .flatMap { bootstrapWithNonZeroRound =>
                config.onboarding match {
                  case Some(onboardingConfig) if bootstrapWithNonZeroRound.supported =>
                    onboardingConfig match {
                      case onboardingConfig: FoundDso =>
                        logger.info(
                          s"Setting the configured initial round ${onboardingConfig.initialRound}."
                        )
                        setInitialRound(connection, onboardingConfig.initialRound)
                      case onboardingConfig: JoinWithKey =>
                        logger.info("Setting the initial round given by my sponsor.")
                        setInitialRoundFromSponsor(
                          connection,
                          onboardingConfig,
                          upgradesConfig,
                        )
                      case domainMigrationConfig: DomainMigration =>
                        val migrationDump =
                          loadDomainMigrationDump(domainMigrationConfig.dumpFilePath)
                        val initialRound = migrationDump.participantUsers.users.collectFirst {
                          case user if user.id == config.ledgerApiUser =>
                            user.annotations.get(INITIAL_ROUND_USER_METADATA_KEY)
                        }.flatten match {
                          case None =>
                            logger.info(
                              "Initial round not found in user's metadata dump, defaulting to 0."
                            )
                            "0"
                          case Some(rnd) =>
                            logger.info(
                              s"Setting the initial round to $rnd from migration user's metadata dump."
                            )
                            rnd
                        }
                        setInitialRound(connection, initialRound.toLong)
                    }
                  case Some(_) =>
                    logger.debug(
                      "Feature to set initial round to non-zero not supported, setting it to 0."
                    )
                    setInitialRound(connection, 0L)
                  case None =>
                    logger.debug(
                      "No SV onboarding config was found, setting the initial round to 0."
                    )
                    setInitialRound(connection, 0L)
                }
              }
        }
    } yield initialRound
  }

  // Ensure OTK mappings contain only signing keys that are signed
  protected def ensureCantonNodesOTKRotatedIfNeeded(
      skipSynchronizerInitialization: Boolean,
      cantonIdentifierConfig: SvCantonIdentifierConfig,
      localSynchronizerNode: Option[LocalSynchronizerNode],
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      decentralizedSynchronizerId: SynchronizerId,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] =
    localSynchronizerNode match {
      case Some(synchronizerNode) if !skipSynchronizerInitialization =>
        SynchronizerNodeInitializer.rotateCantonNodesOTKIfNeeded(
          cantonIdentifierConfig,
          synchronizerNode,
          clock,
          loggerFactory,
          retryProvider,
          decentralizedSynchronizerId,
        )
      case _ =>
        logger.info(
          "Skipping OTK keys rotation because skipSynchronizerInitialization is enabled"
        )
        Future.unit
    }

  private def setInitialRound(connection: BaseLedgerConnection, initialRound: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Long] =
    for {
      _ <- SetupUtil.ensureInitialRoundMetadataAnnotation(
        connection,
        config,
        initialRound.toString,
      )
    } yield initialRound

  private def setInitialRoundFromSponsor(
      connection: BaseLedgerConnection,
      joiningConfig: JoinWithKey,
      upgradesConfig: UpgradesConfig,
  )(implicit
      tc: TraceContext,
      ece: ExecutionContextExecutor,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      mat: Materializer,
  ): Future[Long] =
    for {
      initialRound <- {
        val sponsorConfig = joiningConfig.svClient.adminApi
        retryProvider.getValueWithRetries(
          RetryFor.InitializingClientCalls,
          "initial_round_from_sponsor",
          s"Initial Round from sponsoring SV ${joiningConfig.svClient.adminApi}",
          setInitialRoundFromSponsor(sponsorConfig, upgradesConfig),
          logger,
        )
      }
      _ <- setInitialRound(connection, initialRound.toLong)
    } yield initialRound.toLong

  private def setInitialRoundFromSponsor(
      sponsorConfig: NetworkAppClientConfig,
      upgradesConfig: UpgradesConfig,
  )(implicit
      tc: TraceContext,
      ece: ExecutionContextExecutor,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
      mat: Materializer,
  ): Future[String] =
    SvConnection(
      sponsorConfig,
      upgradesConfig,
      retryProvider,
      loggerFactory,
    ).flatMap { svConnection =>
      svConnection
        .getDsoInfo()
        // the sponsor might use the old api, in this case initial round has to be 0
        .map(_.initialRound.getOrElse("0"))
        .andThen(_ => svConnection.close())
    }

  private def isOnboardedInDecentralizedNamespace(
      svcStore: SvDsoStore
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] = for {
    dsoRules <- svcStore.lookupDsoRules()
    isMemberOfDecentralizedNamespace <-
      participantAdminConnection
        .getDecentralizedNamespaceDefinition(
          dsoRules
            .map(_.domain)
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription("Domain not found in DsoRules")
                .asRuntimeException()
            ),
          svcStore.key.dsoParty.uid.namespace,
          AuthorizedState,
        )
        .map(_.mapping.owners.contains(svcStore.key.svParty.uid.namespace))
  } yield isMemberOfDecentralizedNamespace

}
