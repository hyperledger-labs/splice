// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator

import cats.implicits.{catsSyntaxApplicativeByValue as _, *}
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.User
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.api.util.DurationConversion
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import com.digitalasset.canton.util.MonadUtil
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.*
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.directives.BasicDirectives
import org.lfdecentralizedtrust.splice.admin.api.TraceContextDirectives.withTraceContext
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpErrorHandler}
import org.lfdecentralizedtrust.splice.auth.*
import org.lfdecentralizedtrust.splice.automation.{
  DomainParamsAutomationService,
  DomainTimeAutomationService,
}
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, SharedSpliceAppParameters}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.http.v0.status.wallet.WalletResource as StatusWalletResource
import org.lfdecentralizedtrust.splice.http.v0.external.ans.AnsResource
import org.lfdecentralizedtrust.splice.http.v0.external.wallet.WalletResource as ExternalWalletResource
import org.lfdecentralizedtrust.splice.http.v0.scanproxy.ScanproxyResource
import org.lfdecentralizedtrust.splice.http.v0.validator.ValidatorResource
import org.lfdecentralizedtrust.splice.http.v0.validator_admin.ValidatorAdminResource
import org.lfdecentralizedtrust.splice.http.v0.validator_public.ValidatorPublicResource
import org.lfdecentralizedtrust.splice.http.v0.wallet.WalletResource as InternalWalletResource
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesStore
import org.lfdecentralizedtrust.splice.migration.{
  DomainDataRestorer,
  DomainMigrationInfo,
  MigrationTimeInfo,
  ParticipantUsersDataRestorer,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{
  BftScanConnection,
  MinimalScanConnection,
  SingleScanConnection,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.setup.{NodeInitializer, ParticipantInitializer}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.store.{AppStoreWithIngestion, HistoryMetrics, UpdateHistory}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.ValidatorApp.OAuthRealms
import org.lfdecentralizedtrust.splice.validator.admin.http.*
import org.lfdecentralizedtrust.splice.validator.automation.{
  ValidatorAutomationService,
  ValidatorPackageVettingTrigger,
}
import org.lfdecentralizedtrust.splice.validator.config.*
import org.lfdecentralizedtrust.splice.validator.domain.DomainConnector
import org.lfdecentralizedtrust.splice.validator.metrics.ValidatorAppMetrics
import org.lfdecentralizedtrust.splice.validator.migration.{
  DomainMigrationDump,
  ParticipantPartyMigrator,
}
import org.lfdecentralizedtrust.splice.validator.store.{
  ValidatorConfigProvider,
  ValidatorInternalStore,
  ValidatorStore,
}
import org.lfdecentralizedtrust.splice.validator.util.{ValidatorScanConnection, ValidatorUtil}
import org.lfdecentralizedtrust.splice.wallet.admin.http.{
  HttpExternalWalletHandler,
  HttpStatusWalletHandler,
  HttpWalletHandler,
  UserWalletAuthExtractor,
}
import org.lfdecentralizedtrust.splice.wallet.automation.UserWalletAutomationService
import org.lfdecentralizedtrust.splice.wallet.util.ValidatorTopupConfig
import org.lfdecentralizedtrust.splice.wallet.{ExternalPartyWalletManager, UserWalletManager}
import org.lfdecentralizedtrust.tokenstandard.allocation.v1.Resource as TokenStandardAllocationResource
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v1.Resource as TokenStandardAllocationInstructionResource
import org.lfdecentralizedtrust.tokenstandard.metadata.v1.Resource as TokenStandardMetadataResource
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.Resource as TokenStandardTransferInstructionResource

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

/** Class representing a Validator app instance. */
class ValidatorApp(
    override val name: InstanceName,
    val config: ValidatorAppBackendConfig,
    val amuletAppParameters: SharedSpliceAppParameters,
    storage: DbStorage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    metrics: ValidatorAppMetrics,
    adminRoutes: AdminRoutes,
)(implicit
    ac: ActorSystem,
    esf: ExecutionSequencerFactory,
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends Node[ValidatorApp.State, Option[CantonTimestamp]](
      config.ledgerApiUser,
      config.participantClient,
      amuletAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      metrics,
    )
    with BasicDirectives {

  override def packagesForJsonDecoding =
    super.packagesForJsonDecoding ++ DarResources.wallet.all ++ DarResources.amuletNameService.all ++ DarResources.dsoGovernance.all

  // We don't want the validator to mess around with things like sequencer connections until the SV app finishes init
  override def waitForPartyBeforePreinitialize = false

  override def preInitializeBeforeLedgerConnection()(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    // TODO(tech-debt) consider removing early version check once we switch to a non-dev Canton protocol version
    _ <- ensureVersionMatch(config.scanClient)
    _ <- withParticipantAdminConnection { participantAdminConnection =>
      readRestoreDump match {
        case Some(migrationDump) =>
          logger.info(
            "We're restoring from a migration dump, ensuring participant is initialized"
          )
          val nodeInitializer =
            new NodeInitializer(participantAdminConnection, retryProvider, loggerFactory)
          nodeInitializer.initializeFromDumpAndWait(
            migrationDump.participant
          )
        case None =>
          UpdateHistory.getHighestKnownMigrationId(storage).flatMap {
            case Some(migrationId)
                if !config.svValidator && migrationId < config.domainMigrationId =>
              throw Status.INVALID_ARGUMENT
                .withDescription(
                  s"Migration ID was incremented (to ${config.domainMigrationId}) but no migration dump for restoring from was specified."
                )
                .asRuntimeException()
            case _ =>
              val cantonIdentifierConfig =
                ValidatorCantonIdentifierConfig.resolvedNodeIdentifierConfig(config)
              val participantInitializer = new ParticipantInitializer(
                cantonIdentifierConfig.participant,
                config.participantBootstrappingDump,
                loggerFactory,
                retryProvider,
                participantAdminConnection,
              )
              if (config.svValidator) {
                logger.info("Waiting for the participant to be initialized by the SV app")
                participantInitializer.waitForNodeInitialized()
              } else {
                logger.info(
                  "Ensuring participant is initialized"
                )
                participantInitializer.ensureInitializedWithExpectedId()
              }
          }
      }
    }
  } yield ()

  override def preInitializeAfterLedgerConnection(
      connection: BaseLedgerConnection,
      ledgerClient: SpliceLedgerClient,
  )(implicit traceContext: TraceContext): scala.concurrent.Future[Option[CantonTimestamp]] =
    for {
      initialSynchronizerTime <-
        withParticipantAdminConnection { participantAdminConnection =>
          for {
            participantId <- participantAdminConnection.getParticipantId()
            internalStore <- ValidatorInternalStore(
              participantId,
              validatorParty = ParticipantPartyMigrator.toPartyId(
                config.validatorPartyHint
                  .getOrElse(
                    BaseLedgerConnection.sanitizeUserIdToPartyString(config.ledgerApiUser)
                  ),
                participantId,
              ),
              storage,
              loggerFactory,
            )
            configProvider = new ValidatorConfigProvider(internalStore, loggerFactory)

            scanConnection <- appInitStep("Getting BFT scan connection") {
              client.BftScanConnection(
                ledgerClient,
                config.scanClient,
                amuletAppParameters.upgradesConfig,
                clock,
                retryProvider,
                loggerFactory,
                ValidatorScanConnection.getPersistedScanList(configProvider),
                ValidatorScanConnection.persistScanUrlListBuilder(configProvider),
              )
            }
            domainConnector = new DomainConnector(
              config,
              participantAdminConnection,
              scanConnection,
              config.domainMigrationId,
              retryProvider,
              loggerFactory,
            )
            domainAlreadyRegistered <- participantAdminConnection
              .lookupSynchronizerConnectionConfig(config.domains.global.alias)
              .map(_.isDefined)
            now = clock.now
            // This is used by the ReconcileSequencerConnectionsTrigger to avoid travelling back in time if the domain time is behind this.
            // We want to avoid using this when we already have a synchronizer connection as then synchronizer time should be used so we
            // only use it when the domain has not been registered at all.
            // Note that the logic below is also a bit dodgy as it uses CantonTimestamp.now
            // even if we have already registered which could be an issue after a restart.
            // For now this seems acceptable.
            initialSynchronizerTime = Option.when(!domainAlreadyRegistered)(now)
            _ <- readRestoreDump match {
              case Some(migrationDump) =>
                for {
                  allSequencerConnections <- domainConnector
                    .getDecentralizedSynchronizerSequencerConnections(clock)
                  sequencerConnections = allSequencerConnections.values.toSeq match {
                    case Seq() =>
                      sys.error("Expected at least one sequencer connection but got 0")
                    case Seq(connections) => connections
                    // TODO (DACH-NY/canton-network-node#13301) handle this in a cleaner way (or just drop hard domain migration support at some point)
                    case _ =>
                      sys.error(
                        s"Hard domain migrations and soft domain migrations are incompatible, got sequencer connections: $allSequencerConnections"
                      )
                  }
                  _ <- appInitStep("Connecting domain and restoring data") {
                    val decentralizedSynchronizerInitializer = new DomainDataRestorer(
                      participantAdminConnection,
                      config.timeTrackerMinObservationDuration,
                      config.timeTrackerObservationLatency,
                      config.parameters.enabledFeatures.newSequencerConnectionPool,
                      loggerFactory,
                    )
                    decentralizedSynchronizerInitializer.connectDomainAndRestoreData(
                      config.domains.global.alias,
                      migrationDump.domainId,
                      sequencerConnections,
                      migrationDump.dars,
                      migrationDump.acsSnapshot,
                      legacyAcsImport =
                        migrationDump.acsFormat == http.DomainMigrationDump.AcsFormat.AdminApi,
                    )
                  }
                  _ <- appInitStep("Restoring participant users data") {
                    val readWriteConnection = ledgerClient.connection(
                      this.getClass.getSimpleName,
                      loggerFactory,
                      SpliceCircuitBreaker(
                        "restore",
                        config.parameters.circuitBreakers.mediumPriority,
                        clock,
                        loggerFactory,
                      )(ac.scheduler, implicitly),
                    )
                    val participantUsersDataRestorer = new ParticipantUsersDataRestorer(
                      readWriteConnection,
                      loggerFactory,
                    )
                    participantUsersDataRestorer.restoreParticipantUsersData(
                      migrationDump.participantUsers
                    )
                  }
                } yield ()
              case None =>
                if (config.svValidator && config.disableSvValidatorBftSequencerConnection)
                  appInitStep("Ensuring decentralized synchronizer already registered") {
                    domainConnector.waitForDecentralizedSynchronizerIsRegisteredAndConnected()
                  }
                else
                  appInitStep("Ensuring decentralized synchronizer registered") {
                    domainConnector
                      .ensureDecentralizedSynchronizerRegisteredAndConnectedWithCurrentConfig(clock)
                  }
            }
            _ <- appInitStep("Ensuring extra domains registered") {
              domainConnector.ensureExtraDomainsRegistered()
            }
            // Prevet early to make sure we have the required packages even
            // before the automation kicks in.
            _ <- appInitStep("Vet packages") {
              for {
                amuletRules <- scanConnection.getAmuletRules()
                globalSynchronizerId: SynchronizerId <- scanConnection.getAmuletRulesDomain()(
                  traceContext
                )
                // vet on extra synchronizers as well
                // TODO(#2742) make sure we also vet on later connection + on upgrades (and maybe move below logic)
                extraSynchronizerAliases: Set[SynchronizerAlias] = config.domains.extra
                  .map(_.alias)
                  .toSet
                allConnectedSynchronizers <- participantAdminConnection.listConnectedDomains()
                extraSynchronizerIds: Seq[SynchronizerId] = allConnectedSynchronizers
                  .filter(result => extraSynchronizerAliases.contains(result.synchronizerAlias))
                  .map(_.physicalSynchronizerId.logical)
                packageVetting = new PackageVetting(
                  ValidatorPackageVettingTrigger.packages,
                  clock,
                  participantAdminConnection,
                  loggerFactory,
                  config.latestPackagesOnly,
                )
                _ <-
                  MonadUtil.sequentialTraverse_(Seq(globalSynchronizerId) ++ extraSynchronizerIds) {
                    synchronizerId =>
                      packageVetting.vetCurrentPackages(synchronizerId, amuletRules)
                  }
              } yield ()
            }
            _ <- (config.migrateValidatorParty, config.participantBootstrappingDump) match {
              case (
                    Some(MigrateValidatorPartyConfig(scanConfig, partiesToMigrate)),
                    Some(participantBootstrappingConfig),
                  ) =>
                val validatorPartyHint = config.validatorPartyHint
                  .getOrElse(
                    BaseLedgerConnection.sanitizeUserIdToPartyString(config.ledgerApiUser)
                  )

                val participantPartyMigrator = new ParticipantPartyMigrator(
                  connection,
                  participantAdminConnection,
                  config.domains.global.alias,
                  configProvider,
                  loggerFactory,
                )
                appInitStep("Migrating party data") {
                  for {
                    nodeIdentitiesDump <- ParticipantInitializer.getDump(
                      participantBootstrappingConfig
                    )
                    _ <- participantPartyMigrator
                      .migrate(
                        nodeIdentitiesDump,
                        validatorPartyHint,
                        config.ledgerApiUser,
                        config.domains.global.alias,
                        partyId =>
                          getAcsSnapshotFromSingleScan(
                            scanConfig,
                            partyId,
                            logger,
                            retryProvider,
                          ),
                        partiesToMigrate.map(_.map(party => PartyId.tryFromProtoPrimitive(party))),
                      )
                  } yield ()
                }
              case (Some(_), None) =>
                sys.error(
                  "ParticipantBootstrappingDumpConfig is required if MigrateValidatorPartyConfig is set"
                )
              case (None, _) => {
                // Note that for the validator of an SV app, the user will be created by the SV app with a
                // primary party set to the SV app already so this is a noop.
                appInitStep("Ensuring user primary party is allocated") {
                  {
                    val hint = config.validatorPartyHint
                      .getOrElse(
                        throw Status.NOT_FOUND
                          .withDescription("Missing validator party hint for non-SV validator")
                          .asRuntimeException()
                      )
                    connection.getOptionalPrimaryParty(config.ledgerApiUser).flatMap {
                      case None =>
                        // during HDM the party is not assigned to the user yet, but it's allocated on the participant
                        connection.getPartyByHint(hint, participantAdminConnection).flatMap {
                          case Some(_) =>
                            logger.info("Party already allocated but not assigned as primary")
                            connection.ensureUserPrimaryPartyIsAllocated(
                              config.ledgerApiUser,
                              hint,
                              participantAdminConnection,
                            )
                          case None =>
                            // A party has not yet been allocated
                            // Enforce hint format before allocating it
                            val pattern = "^[a-zA-Z0-9_]+-[a-zA-Z0-9_]+-[0-9]+$".r
                            pattern.findFirstMatchIn(hint) match {
                              case None =>
                                throw Status.INVALID_ARGUMENT
                                  .withDescription(
                                    s"Validator party hint ($hint) must match pattern <organization>-<function>-<enumerator>, where organization & function are alphanumerical, and enumerator is an integer"
                                  )
                                  .asRuntimeException()
                              case Some(_) =>
                            }
                            appInitStep(
                              "Creating user primary party and waiting for it to be allocated"
                            ) {
                              connection.ensureUserPrimaryPartyIsAllocated(
                                config.ledgerApiUser,
                                hint,
                                participantAdminConnection,
                              )
                            }
                        }
                      case Some(partyId) =>
                        val existingHint = partyId.uid.identifier.str
                        if (existingHint != hint) {
                          throw Status.INVALID_ARGUMENT
                            .withDescription(
                              s"PartyId hint $existingHint does not match configured hint $hint."
                            )
                            .asRuntimeException()
                        } else {
                          logger.debug(s"PartyId matches the configured hint $hint")

                        }
                        Future.successful(())
                    }
                  } whenA !config.svValidator
                }
              }
            }
            _ <- participantAdminConnection.ensurePruningSchedule(config.participantPruningSchedule)
          } yield initialSynchronizerTime
        }
    } yield initialSynchronizerTime

  private def readRestoreDump: Option[DomainMigrationDump] = config.restoreFromMigrationDump.map {
    path =>
      if (config.svValidator)
        throw Status.INVALID_ARGUMENT
          .withDescription("SV Validator should not be configured with a dump file")
          .asRuntimeException()

      val migrationDump = BackupDump.readFromPath[DomainMigrationDump](path) match {
        case Failure(exception) =>
          throw Status.INVALID_ARGUMENT
            .withDescription(s"Failed to read migration dump from $path: ${exception.getMessage}")
            .asRuntimeException()
        case Success(value) => value
      }
      if (migrationDump.migrationId != config.domainMigrationId)
        throw Status.INVALID_ARGUMENT
          .withDescription(
            s"Migration id from the dump ${migrationDump.migrationId} does not match the configured migration id in the validator ${config.domainMigrationId}. Please check if the validator app is configured with the correct migration id"
          )
          .asRuntimeException()
      migrationDump
  }

  private def getAcsSnapshotFromSingleScan(
      scanConfig: ScanAppClientConfig,
      partyId: PartyId,
      logger: TracedLogger,
      retryProvider: RetryProvider,
  )(implicit traceContext: TraceContext): Future[ByteString] =
    retryProvider.retry(
      RetryFor.WaitingOnInitDependency,
      "get_acs_snapshot_from_single_scan",
      "get ACS snapshot from single scan",
      SingleScanConnection.withSingleScanConnection(
        scanConfig,
        amuletAppParameters.upgradesConfig,
        clock,
        retryProvider,
        loggerFactory,
      ) { scanConnection =>
        // We don't set the record time for now here. We assume recover node from
        // keys
        scanConnection.getAcsSnapshot(partyId, recordTime = None)
      },
      logger,
    )

  private def setupAppInstance(
      name: String,
      instance: AppInstance,
      validatorParty: PartyId,
      storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerId: SynchronizerId,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info(s"Attempting to setup app $name...")
    for {
      _ <- MonadUtil.sequentialTraverse_(instance.dars)(dar =>
        participantAdminConnection.uploadDarFileWithVettingOnAllConnectedSynchronizers(
          dar,
          RetryFor.WaitingOnInitDependency,
        )
      )
      party <- storeWithIngestion
        .connection(SpliceLedgerConnectionPriority.Medium)
        .getOrAllocateParty(
          instance.serviceUser,
          Seq(new User.Right.CanReadAs(validatorParty.toProtoPrimitive)),
          participantAdminConnection,
        )
      _ <- ValidatorUtil
        .onboard(
          instance.walletUser.getOrElse(instance.serviceUser),
          Some(party),
          Some(false),
          storeWithIngestion,
          validatorUserName = config.ledgerApiUser,
          // we're initializing so AmuletRules is guaranteed to be on synchronizerId
          getAmuletRulesDomain = () => _ => Future successful synchronizerId,
          participantAdminConnection,
          retryProvider,
          logger,
          CommandPriority.High,
          RetryFor.WaitingOnInitDependency,
        )
    } yield {
      logger.info(
        s"Setup app $name with service user ${instance.serviceUser}, wallet user ${instance.walletUser}  primary party $party, and uploaded ${instance.dars}."
      )
    }
  }

  private def ensureValidatorIsOnboarded(
      store: ValidatorStore,
      validatorParty: PartyId,
      onboardingConfig: Option[ValidatorOnboardingConfig],
  )(implicit traceContext: TraceContext): Future[Unit] = {
    store.lookupValidatorLicenseWithOffset().flatMap {
      case QueryResult(_, Some(_)) =>
        logger.info("ValidatorLicense found => already onboarded.")
        Future.successful(())
      case _ =>
        onboardingConfig match {
          case Some(oc) =>
            logger.info(
              "ValidatorLicense not found, onboarding is configured. Requesting onboarding with configured secret"
            )
            for {
              _ <- requestOnboarding(oc.svClient.adminApi, validatorParty, oc.secret)
              _ <- waitForValidatorLicense(store)
            } yield ()
          case None =>
            logger.info(
              "ValidatorLicense not found, onboarding is not configured. Wait for the ValidatorLicense"
            )
            waitForValidatorLicense(store)
        }
    }
  }

  private def waitForValidatorLicense(
      store: ValidatorStore
  )(implicit traceContext: TraceContext): Future[Unit] = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "validator_license",
      show"ValidatorLicense for ${store.key.validatorParty} is visible",
      for {
        validatorLicenseResult <- store.lookupValidatorLicenseWithOffset()
        _ <- validatorLicenseResult match {
          case QueryResult(_, Some(_)) => Future.successful(())
          case _ =>
            throw Status.NOT_FOUND
              .withDescription(
                show"ValidatorLicense for ${store.key.validatorParty}"
              )
              .asRuntimeException()
        }
      } yield (),
      logger,
    )
  }

  private def ensureVersionMatch(scanClientConfig: BftScanClientConfig)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "version_check",
      "version checked via scan",
      // we checkVersionCompatibility on every Splice app connection
      scanClientConfig match {
        case BftScanClientConfig.TrustSingle(url, _) =>
          val config = ScanAppClientConfig(NetworkAppClientConfig(url))
          MinimalScanConnection(
            config,
            amuletAppParameters.upgradesConfig,
            retryProvider,
            loggerFactory,
          ).flatMap(con => con.checkActive().andThen(_ => con.close()))
        case BftScanClientConfig.BftCustom(seedUrls, _, _, _, _, _) =>
          seedUrls
            .traverse { url =>
              val config = ScanAppClientConfig(NetworkAppClientConfig(url))
              MinimalScanConnection(
                config,
                amuletAppParameters.upgradesConfig,
                retryProvider,
                loggerFactory,
              ).flatMap(con => con.checkActive().andThen(_ => con.close()))
            }
            .map(_ => ())
        case BftScanClientConfig.Bft(seedUrls, _, _, _) =>
          seedUrls
            .traverse { url =>
              val config = ScanAppClientConfig(NetworkAppClientConfig(url))
              MinimalScanConnection(
                config,
                amuletAppParameters.upgradesConfig,
                retryProvider,
                loggerFactory,
              ).flatMap(con => con.checkActive().andThen(_ => con.close()))
            }
            .map(_ => ())
      },
      logger,
    )

  private def withSvConnection[T](
      svConfig: NetworkAppClientConfig
  )(f: ValidatorSvConnection => Future[T])(implicit traceContext: TraceContext): Future[T] =
    ValidatorSvConnection(
      svConfig,
      amuletAppParameters.upgradesConfig,
      retryProvider,
      loggerFactory,
    ).flatMap(con => f(con).andThen(_ => con.close()))

  private def requestOnboarding(
      svConfig: NetworkAppClientConfig,
      validatorParty: PartyId,
      secret: String,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info(s"Requesting to be onboarded by SV at: ${svConfig.url}")
    retryProvider.retry(
      RetryFor.WaitingOnInitDependency,
      "request_onboarding",
      "request onboarding",
      withSvConnection(svConfig)(_.onboardValidator(validatorParty, secret, config.contactPoint)),
      logger,
    )
  }

  private def withParticipantAdminConnection[T](f: ParticipantAdminConnection => Future[T]) = {
    val participantAdminConnection = new ParticipantAdminConnection(
      config.participantClient.adminApi,
      amuletAppParameters.loggingConfig.api,
      loggerFactory,
      metrics.grpcClientMetrics,
      retryProvider,
    )
    f(participantAdminConnection).andThen { _ => participantAdminConnection.close() }
  }

  private def newTrafficBalanceService(
      participantAdminConnection: ParticipantAdminConnection,
      scanConnection: BftScanConnection,
  )(implicit traceContext: TraceContext) = {
    def lookupReservedTraffic(synchronizerId: SynchronizerId): Future[Option[NonNegativeLong]] = {
      config.domains.global.reservedTrafficO
        .fold(Future.successful(Option.empty[NonNegativeLong]))(reservedTraffic => {
          for {
            amuletRules <- scanConnection.getAmuletRulesWithState()
            amuletConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(clock.now)
            reservedTrafficO = Option.when(
              amuletConfig.decentralizedSynchronizer.requiredSynchronizers.map
                .containsKey(synchronizerId.toProtoPrimitive)
            )(reservedTraffic)
          } yield reservedTrafficO
        })
    }

    TrafficBalanceService(
      lookupReservedTraffic,
      participantAdminConnection,
      clock,
      config.domains.global.trafficBalanceCacheTimeToLive,
      loggerFactory,
    )
  }

  override def initialize(
      ledgerClient: SpliceLedgerClient,
      validatorParty: PartyId,
      initialSynchronizerTime: Option[CantonTimestamp],
  )(implicit traceContext: TraceContext): Future[ValidatorApp.State] =
    for {
      _ <- Future.unit
      readOnlyLedgerConnection = ledgerClient
        .readOnlyConnection(
          this.getClass.getSimpleName,
          loggerFactory,
        )
      participantAdminConnection = new ParticipantAdminConnection(
        config.participantClient.adminApi,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        metrics.grpcClientMetrics,
        retryProvider,
      )
      participantIdentitiesStore = new NodeIdentitiesStore(
        participantAdminConnection,
        config.participantIdentitiesBackup.map(_ -> clock),
        loggerFactory,
      )

      participantId <- appInitStep("Get participant id") {
        participantAdminConnection.getParticipantId()
      }
      internalStore <- ValidatorInternalStore(
        participantId,
        validatorParty = validatorParty,
        storage,
        loggerFactory,
      )

      configProvider = new ValidatorConfigProvider(
        internalStore,
        loggerFactory,
      )

      scanConnection <- appInitStep("Get scan connection") {
        client.BftScanConnection(
          ledgerClient,
          config.scanClient,
          amuletAppParameters.upgradesConfig,
          clock,
          retryProvider,
          loggerFactory,
          ValidatorScanConnection.getPersistedScanList(configProvider),
          ValidatorScanConnection.persistScanUrlListBuilder(configProvider),
        )
      }

      // Register the traffic balance service
      trafficBalanceService = newTrafficBalanceService(participantAdminConnection, scanConnection)
      _ = ledgerClient.registerTrafficBalanceService(trafficBalanceService)

      // All ledger commands submitted by the validator party past this point during initialization
      // must have their priority set as CommandPriority.High to ensure that they are not blocked by
      // the traffic balance service while the first top-up for the validator is yet to go through.

      dsoParty <- appInitStep("Get DSO party id") {
        scanConnection.getDsoPartyIdWithRetries()
      }

      key = ValidatorStore.Key(
        validatorParty = validatorParty,
        dsoParty = dsoParty,
      )
      domainMigrationInfo <-
        if (config.svValidator) {
          appInitStep(s"Get domain migration info from ${config.svUser}") {
            DomainMigrationInfo.loadFromUserMetadata(
              readOnlyLedgerConnection,
              config.svUser.getOrElse(throw new Exception("svUser is required for an sv Validator")),
            )
          }
        } else {
          val dump = readRestoreDump
          Future.successful(
            // TODO(DACH-NY/canton-network-node#9731): get migration id from sponsor sv / scan instead of configuring here
            DomainMigrationInfo(
              config.domainMigrationId,
              dump.map(d =>
                MigrationTimeInfo(
                  CantonTimestamp.assertFromInstant(d.acsTimestamp),
                  d.synchronizerWasPaused,
                )
              ),
            )
          )
        }

      store = ValidatorStore(
        key,
        storage,
        loggerFactory,
        retryProvider,
        domainMigrationInfo,
        participantId,
        config.automation.ingestion,
      )
      validatorUpdateHistory = new UpdateHistory(
        storage,
        domainMigrationInfo,
        store.storeName,
        participantId,
        store.acsContractFilter.ingestionFilter.primaryParty,
        BackfillingRequirement.BackfillingNotRequired,
        loggerFactory,
        enableissue12777Workaround = false,
        enableImportUpdateBackfill = false,
        HistoryMetrics(retryProvider.metricsFactory, domainMigrationInfo.currentMigrationId),
      )
      domainTimeAutomationService = new DomainTimeAutomationService(
        config.domains.global.alias,
        participantAdminConnection,
        config.automation,
        clock,
        retryProvider,
        loggerFactory,
      )
      domainParamsAutomationService = new DomainParamsAutomationService(
        config.domains.global.alias,
        participantAdminConnection,
        config.automation,
        clock,
        retryProvider,
        loggerFactory,
      )
      validatorTopupConfig = ValidatorTopupConfig(
        config.domains.global.buyExtraTraffic.targetThroughput,
        config.domains.global.buyExtraTraffic.minTopupInterval,
        config.automation.topupTriggerPollingInterval_,
      )
      dedupDuration = DedupDuration(
        com.google.protobuf.duration.Duration
          .toJavaProto(DurationConversion.toProto(config.deduplicationDuration.asJavaApproximation))
      )
      synchronizerId <- scanConnection.getAmuletRulesDomain()(traceContext)
      cantonIdentifierConfig =
        ValidatorCantonIdentifierConfig.resolvedNodeIdentifierConfig(config)
      _ <- ParticipantInitializer.ensureInitializedWithRotatedOTK(
        cantonIdentifierConfig.participant,
        participantAdminConnection,
        config.participantBootstrappingDump,
        loggerFactory,
        retryProvider,
        synchronizerId,
      )
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        synchronizerId,
        readOnlyLedgerConnection,
        loggerFactory,
      )

      walletManagerOpt =
        if (config.enableWallet) {
          val externalPartyWalletManager = new ExternalPartyWalletManager(
            ledgerClient,
            store,
            config.ledgerApiUser,
            config.automation,
            clock,
            domainTimeAutomationService.domainTimeSync,
            domainParamsAutomationService.domainUnpausedSync,
            storage,
            retryProvider,
            loggerFactory,
            domainMigrationInfo,
            participantId,
            config.ingestFromParticipantBegin,
            config.ingestUpdateHistoryFromParticipantBegin,
            config.parameters,
            scanConnection,
          )
          val walletManager = new UserWalletManager(
            ledgerClient,
            store,
            config.ledgerApiUser,
            externalPartyWalletManager,
            config.automation,
            clock,
            domainTimeAutomationService.domainTimeSync,
            domainParamsAutomationService.domainUnpausedSync,
            config.treasury,
            storage,
            retryProvider,
            scanConnection,
            packageVersionSupport,
            loggerFactory,
            domainMigrationInfo,
            participantId,
            config.ingestFromParticipantBegin,
            config.ingestUpdateHistoryFromParticipantBegin,
            validatorTopupConfig,
            config.walletSweep,
            config.autoAcceptTransfers,
            dedupDuration,
            txLogBackfillEnabled = config.txLogBackfillEnabled,
            txLogBackfillingBatchSize = config.txLogBackfillBatchSize,
            config.parameters,
          )
          Some(walletManager)
        } else {
          logger.info("Not starting wallet as it's disabled")
          None
        }
      automation = new ValidatorAutomationService(
        config.automation,
        config.participantIdentitiesBackup,
        validatorTopupConfig,
        config.domains.global.buyExtraTraffic.grpcDeadline,
        config.transferPreapproval,
        config.domains.global.url.isEmpty,
        config.svValidator,
        clock,
        domainTimeAutomationService.domainTimeSync,
        domainParamsAutomationService.domainUnpausedSync,
        walletManagerOpt,
        store,
        validatorUpdateHistory,
        storage,
        scanConnection,
        ledgerClient,
        participantAdminConnection,
        participantIdentitiesStore,
        new DomainConnector(
          config,
          participantAdminConnection,
          scanConnection,
          config.domainMigrationId,
          retryProvider,
          loggerFactory,
        ),
        config.domainMigrationDumpPath,
        config.domainMigrationId,
        retryProvider,
        config.ingestFromParticipantBegin,
        config.ingestUpdateHistoryFromParticipantBegin,
        config.svValidator,
        config.sequencerRequestAmplificationPatience,
        config.contactPoint,
        initialSynchronizerTime,
        config.maxVettingDelay,
        config.parameters,
        config.latestPackagesOnly,
        config.parameters.enabledFeatures,
        loggerFactory,
      )
      _ <- MonadUtil.sequentialTraverse_(config.appInstances.toList)({ case (name, instance) =>
        appInitStep(s"Set up app instance $name") {
          setupAppInstance(
            name,
            instance,
            validatorParty,
            automation,
            participantAdminConnection,
            synchronizerId,
          )
        }
      })
      _ <- appInitStep(s"Onboard validator wallet users") {
        val users = if (config.validatorWalletUsers.isEmpty) {
          // TODO(#760) also onboard ledgerApiUser if both users are set
          Seq(config.ledgerApiUser)
        } else {
          config.validatorWalletUsers
        }
        MonadUtil.sequentialTraverse_(users) { user =>
          ValidatorUtil.onboard(
            endUserName = user,
            knownParty = Some(validatorParty),
            Some(false),
            automation,
            validatorUserName = config.ledgerApiUser,
            // we're initializing so AmuletRules is guaranteed to be on synchronizerId
            getAmuletRulesDomain = () => _ => Future successful synchronizerId,
            participantAdminConnection,
            retryProvider,
            logger,
            CommandPriority.High,
            RetryFor.WaitingOnInitDependency,
          )
        }
      }
      _ <- appInitStep(s"Ensure validator is onboarded") {
        ensureValidatorIsOnboarded(store, validatorParty, config.onboarding)
      }

      userRightsProvider = new ParticipantUserRightsProvider(
        automation.connection(SpliceLedgerConnectionPriority.Low)
      )

      verifier = config.auth match {
        case AuthConfig.Hs256Unsafe(audience, secret) => new HMACVerifier(audience, secret)
        case AuthConfig.Rs256(audience, jwksUrl, connectionTimeout, readTimeout) =>
          new RSAVerifier(
            audience,
            jwksUrl,
            RSAVerifier.TimeoutsConfig(connectionTimeout, readTimeout),
          )
      }

      handler =
        new HttpValidatorHandler(
          automation,
          validatorUserName = config.ledgerApiUser,
          getAmuletRulesDomain = scanConnection.getAmuletRulesDomain,
          participantAdminConnection,
          retryProvider,
          loggerFactory,
        )

      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        synchronizerId,
        readOnlyLedgerConnection,
        loggerFactory,
      )

      adminHandler =
        new HttpValidatorAdminHandler(
          automation,
          participantIdentitiesStore,
          validatorUserName = config.ledgerApiUser,
          validatorWalletUserNames = config.validatorWalletUsers,
          walletManagerOpt,
          getAmuletRulesDomain = scanConnection.getAmuletRulesDomain,
          scanConnection = scanConnection,
          participantAdminConnection,
          packageVersionSupport,
          config,
          clock,
          retryProvider = retryProvider,
          loggerFactory,
        )

      walletInternalHandler = walletManagerOpt.map(walletManager =>
        (
          new HttpWalletHandler(
            walletManager,
            scanConnection,
            loggerFactory,
            retryProvider,
            validatorTopupConfig,
            dedupDuration,
            packageVersionSupport,
          ),
          walletManager,
        )
      )

      walletExternalHandler = walletManagerOpt.map(walletManager =>
        (
          new HttpExternalWalletHandler(
            walletManager,
            loggerFactory,
            retryProvider,
            participantAdminConnection,
            config.domainMigrationId,
          ),
          walletManager,
        )
      )

      walletStatusHandler = walletManagerOpt.map(walletManager =>
        (
          new HttpStatusWalletHandler(
            walletManager,
            loggerFactory,
            packageVersionSupport,
          ),
          walletManager,
        )
      )

      ansExternalHandler = walletManagerOpt.map(walletManager =>
        (
          new HttpExternalAnsHandler(
            walletManager,
            scanConnection,
            loggerFactory,
            retryProvider,
          ),
          walletManager,
        )
      )

      scanProxyHandler = new HttpScanProxyHandler(
        scanConnection,
        loggerFactory,
      )

      tokenStandardScanProxyHandler = new HttpTokenStandardScanProxyHandler(
        scanConnection,
        loggerFactory,
      )

      publicHandler = new HttpValidatorPublicHandler(
        automation.store,
        config.ledgerApiUser,
        loggerFactory,
      )

      route = cors(
        CorsSettings(ac)
          .withAllowedMethods(
            List(
              HttpMethods.DELETE,
              HttpMethods.GET,
              HttpMethods.POST,
              HttpMethods.HEAD,
              HttpMethods.OPTIONS,
            )
          )
          .withExposedHeaders(Seq("traceparent"))
      ) {
        withTraceContext { implicit traceContext =>
          requestLogger(traceContext) {
            HttpErrorHandler(loggerFactory)(traceContext) {
              concat(
                (Seq(
                  ValidatorResource.routes(
                    handler,
                    operation =>
                      metrics.httpServerMetrics.withMetrics("validator")(operation).tflatMap { _ =>
                        AuthenticationOnlyAuthExtractor(
                          verifier,
                          loggerFactory,
                          OAuthRealms.Validator,
                        )(
                          traceContext
                        )(
                          operation
                        )
                      },
                  ),
                  ScanproxyResource.routes(
                    scanProxyHandler,
                    operation =>
                      metrics.httpServerMetrics.withMetrics("scanProxy")(operation).tflatMap { _ =>
                        AuthenticationOnlyAuthExtractor(
                          verifier,
                          loggerFactory,
                          OAuthRealms.ScanProxy,
                        )(
                          traceContext
                        )(operation)
                      },
                  ),
                  pathPrefix("api" / "validator" / "v0" / "scan-proxy") {
                    concat(
                      TokenStandardMetadataResource.routes(
                        tokenStandardScanProxyHandler,
                        operation => {
                          metrics.httpServerMetrics
                            .withMetrics("tokenStandardMetadata")(operation)
                            .tflatMap { _ =>
                              AuthenticationOnlyAuthExtractor(
                                verifier,
                                loggerFactory,
                                OAuthRealms.ScanProxy,
                              )(
                                traceContext
                              )(
                                operation
                              )
                            }
                        },
                      ),
                      TokenStandardTransferInstructionResource.routes(
                        tokenStandardScanProxyHandler,
                        operation =>
                          metrics.httpServerMetrics
                            .withMetrics("tokenStandardTransfer")(operation)
                            .tflatMap { _ =>
                              AuthenticationOnlyAuthExtractor(
                                verifier,
                                loggerFactory,
                                OAuthRealms.ScanProxy,
                              )(
                                traceContext
                              )(
                                operation
                              )
                            },
                      ),
                      TokenStandardAllocationInstructionResource.routes(
                        tokenStandardScanProxyHandler,
                        operation =>
                          metrics.httpServerMetrics
                            .withMetrics("tokenStandardAllocationInstruction")(operation)
                            .tflatMap { _ =>
                              AuthenticationOnlyAuthExtractor(
                                verifier,
                                loggerFactory,
                                OAuthRealms.ScanProxy,
                              )(
                                traceContext
                              )(
                                operation
                              )
                            },
                      ),
                      TokenStandardAllocationResource.routes(
                        tokenStandardScanProxyHandler,
                        operation =>
                          metrics.httpServerMetrics
                            .withMetrics("tokenStandardAllocation")(operation)
                            .tflatMap { _ =>
                              AuthenticationOnlyAuthExtractor(
                                verifier,
                                loggerFactory,
                                OAuthRealms.ScanProxy,
                              )(
                                traceContext
                              )(
                                operation
                              )
                            },
                      ),
                    )
                  },
                  ValidatorAdminResource.routes(
                    adminHandler,
                    operationId =>
                      metrics.httpServerMetrics
                        .withMetrics("admin")(operationId)
                        .tflatMap { _ =>
                          AdminAuthExtractor(
                            verifier,
                            validatorParty,
                            userRightsProvider,
                            loggerFactory,
                            OAuthRealms.ValidatorOperator,
                          )(traceContext)(operationId)
                        },
                  ),
                  ValidatorPublicResource.routes(
                    publicHandler,
                    operation =>
                      metrics.httpServerMetrics
                        .withMetrics("public")(operation)
                        .tflatMap { _ => provide(()) },
                  ),
                ) ++ walletInternalHandler.toList.map { case (walletHandler, walletManager) =>
                  InternalWalletResource.routes(
                    walletHandler,
                    operation =>
                      metrics.httpServerMetrics
                        .withMetrics("walletInternal")(operation)
                        .tflatMap { _ =>
                          UserWalletAuthExtractor(
                            verifier,
                            walletManager,
                            userRightsProvider,
                            loggerFactory,
                            OAuthRealms.Wallet,
                          )(
                            traceContext
                          )(operation)
                        },
                  )
                } ++ walletExternalHandler.toList.map { case (walletHandler, walletManager) =>
                  ExternalWalletResource.routes(
                    walletHandler,
                    operation =>
                      metrics.httpServerMetrics
                        .withMetrics("walletExternal")(operation)
                        .tflatMap { _ =>
                          UserWalletAuthExtractor(
                            verifier,
                            walletManager,
                            userRightsProvider,
                            loggerFactory,
                            OAuthRealms.Wallet,
                          )(
                            traceContext
                          )(operation)
                        },
                  )
                } ++ walletStatusHandler.toList.map { case (walletHandler, walletManager) =>
                  StatusWalletResource.routes(
                    walletHandler,
                    operation =>
                      metrics.httpServerMetrics
                        .withMetrics("walletStatus")(operation)
                        .tflatMap { _ =>
                          AuthenticationOnlyAuthExtractor(
                            verifier,
                            loggerFactory,
                            OAuthRealms.Wallet,
                          )(
                            traceContext
                          )(operation)
                        },
                  )
                } ++ ansExternalHandler.toList.map { case (ansHandler, walletManager) =>
                  AnsResource.routes(
                    ansHandler,
                    operation =>
                      metrics.httpServerMetrics
                        .withMetrics("ans")(operation)
                        .tflatMap { _ =>
                          UserWalletAuthExtractor(
                            verifier,
                            walletManager,
                            userRightsProvider,
                            loggerFactory,
                            OAuthRealms.Ans,
                          )(traceContext)(
                            operation
                          )
                        },
                  )
                })*
              )
            }
          }
        }
      }
      _ = adminRoutes.updateRoute(route)
    } yield {
      ValidatorApp.State(
        scanConnection,
        participantAdminConnection,
        storage,
        domainTimeAutomationService,
        domainParamsAutomationService,
        store,
        configProvider,
        automation,
        walletManagerOpt,
        timeouts,
        loggerFactory.getTracedLogger(ValidatorApp.State.getClass),
      )
    }

  override lazy val ports = Map("admin" -> config.adminApi.port)

  protected[this] override def automationServices(st: ValidatorApp.State) =
    Seq(st.automation, UserWalletAutomationService)
}

object ValidatorApp {
  case class State(
      scanConnection: BftScanConnection,
      participantAdminConnection: ParticipantAdminConnection,
      storage: Storage,
      domainTimeAutomationService: DomainTimeAutomationService,
      domainParamsAutomationService: DomainParamsAutomationService,
      store: ValidatorStore,
      configProvider: ValidatorConfigProvider,
      automation: ValidatorAutomationService,
      walletManager: Option[UserWalletManager],
      timeouts: ProcessingTimeout,
      logger: TracedLogger,
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean = storage.isActive && automation.isHealthy

    override def close(): Unit =
      LifeCycle.close(
        (Seq(
          participantAdminConnection,
          automation,
        ) ++ walletManager.toList.flatMap { manager =>
          Seq(manager, manager.externalPartyWalletManager)
        } ++ Seq(
          store,
          storage,
          scanConnection,
          domainTimeAutomationService,
          domainParamsAutomationService,
        ))*
      )(logger)
  }

  object OAuthRealms {
    val Validator = "splice validator realm"
    val ValidatorOperator = "splice validator operator realm"
    val ScanProxy = "splice scan proxy realm"
    val Wallet = "splice wallet realm"
    val Ans = "splice ans realm"
  }

}
