// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.EitherT
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.admin.api.client.commands.{
  GrpcAdminCommand,
  ParticipantAdminCommands,
  PruningSchedulerCommands,
}
import com.digitalasset.canton.admin.api.client.data.{
  ListConnectedSynchronizersResult,
  NodeStatus,
  ParticipantStatus,
  PruningSchedule,
}
import com.digitalasset.canton.admin.participant.v30.PruningServiceGrpc.PruningServiceStub
import com.digitalasset.canton.admin.participant.v30.{ExportAcsOldResponse, PruningServiceGrpc}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig, PositiveDurationSeconds}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnectionValidation,
  SequencerConnection,
  SequencerConnections,
}
import com.digitalasset.canton.sequencing.protocol.TrafficState
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  HostingParticipant,
  ParticipantPermission,
  PartyToParticipant,
  SignedTopologyTransaction,
  TopologyChangeOp,
}
import com.digitalasset.canton.topology.{NodeIdentity, ParticipantId, PartyId, PhysicalSynchronizerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.{
  HasParticipantId,
  IMPORT_ACS_WORKFLOW_ID_PREFIX,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  RecreateOnAuthorizedStateChange,
  TopologyResult,
}

import java.time.Instant
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}

/** Connection to the subset of the Canton admin API that we rely
  * on in our own applications.
  */
class ParticipantAdminConnection(
    config: ClientConfig,
    apiLoggingConfig: ApiLoggingConfig,
    loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    retryProvider: RetryProvider,
)(implicit protected val ec: ExecutionContextExecutor, tracer: Tracer)
    extends TopologyAdminConnection(
      config,
      apiLoggingConfig,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )
    with HasParticipantId
    with ParticipantAdminDarsConnection
    with StatusAdminConnection {
  override val serviceName = "Canton Participant Admin API"

  val pruningCommands = new PruningSchedulerCommands[PruningServiceStub](
    PruningServiceGrpc.stub,
    _.setSchedule(_),
    _.clearSchedule(_),
    _.setCron(_),
    _.setMaxDuration(_),
    _.setRetention(_),
    _.getSchedule(_),
  )

  override type Status = ParticipantStatus

  override protected def getStatusRequest: GrpcAdminCommand[?, ?, NodeStatus[ParticipantStatus]] =
    ParticipantAdminCommands.Health.ParticipantStatusCommand()

  def listConnectedDomains()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedSynchronizersResult]] = {
    runCmd(ParticipantAdminCommands.SynchronizerConnectivity.ListConnectedSynchronizers())
  }

  def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] =
    runCmd(getStatusRequest).map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_, _) => false
      case NodeStatus.Success(_) => true
    }

  def getSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[SynchronizerId] =
    getPhysicalSynchronizerId(synchronizerAlias).map(_.logical)

  def getPhysicalSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[PhysicalSynchronizerId] =
    // We avoid ParticipantAdminCommands.SynchronizerConnectivity.GetSynchronizerId which tries to make
    // a new request to the sequencer to query the domain id. ListConnectedSynchronizers
    // on the other hand relies on a cache
    listConnectedDomains().map(
      _.find(
        _.synchronizerAlias == synchronizerAlias
      ).fold(
        throw Status.NOT_FOUND
          .withDescription(s"Domain with alias $synchronizerAlias is not connected")
          .asRuntimeException()
      )(_.physicalSynchronizerId)
    )

  /** Usually you want getSynchronizerId instead which is much faster if the domain is connected
    *  but in some cases we want to check the domain id
    * without risking a full domain connection.
    */
  def getSynchronizerIdWithoutConnecting(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[SynchronizerId] =
    getPhysicalSynchronizerIdWithoutConnecting(synchronizerAlias).map(_.logical)

  def getPhysicalSynchronizerIdWithoutConnecting(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[PhysicalSynchronizerId] =
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.GetSynchronizerId(synchronizerAlias)
    )

  def reconnectAllDomains()(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ReconnectSynchronizers(ignoreFailures =
        false
      )
    )
  }

  def disconnectFromAllDomains()(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    domains <- listConnectedDomains()
    _ <- Future.sequence(
      domains.map(domain =>
        runCmd(
          ParticipantAdminCommands.SynchronizerConnectivity.DisconnectSynchronizer(
            domain.synchronizerAlias
          )
        )
      )
    )
  } yield ()

  private def registerDomain(config: SynchronizerConnectionConfig, handshakeOnly: Boolean)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.RegisterSynchronizer(
        config,
        handshakeOnly,
        SequencerConnectionValidation.ThresholdActive,
      )
    )

  def connectDomain(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    retryProvider.retryForClientCalls(
      "connect_domain",
      s"participant is connected to $alias",
      runCmd(
        ParticipantAdminCommands.SynchronizerConnectivity
          .ReconnectSynchronizer(alias, retry = false)
      ).map(isConnected =>
        if (!isConnected) {
          val msg = s"failed to connect to $alias"
          throw Status.Code.FAILED_PRECONDITION.toStatus.withDescription(msg).asRuntimeException()
        }
      ),
      logger,
    )

  private def disconnectDomain(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(ParticipantAdminCommands.SynchronizerConnectivity.DisconnectSynchronizer(alias))

  def ensureDomainRegistered(
      config: SynchronizerConnectionConfig,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      _ <- retryProvider
        .ensureThat(
          retryFor,
          "domain_registered_handshake",
          s"participant registered ${config.synchronizerAlias} with handshake only",
          lookupSynchronizerConnectionConfig(config.synchronizerAlias).map(_.toRight(())),
          (_: Unit) => registerDomain(config, handshakeOnly = true),
          logger,
        )
    } yield ()
  }

  def ensureDomainRegisteredNoHandshake(
      config: SynchronizerConnectionConfig,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    require(
      config.manualConnect,
      "manualConnect must be true when trying to register only",
    )
    for {
      _ <- retryProvider
        .ensureThat(
          retryFor,
          "domain_registered_no_handshake",
          s"participant registered ${config.synchronizerAlias}",
          lookupSynchronizerConnectionConfig(config.synchronizerAlias).map(_.toRight(())),
          (_: Unit) => registerDomain(config, handshakeOnly = false),
          logger,
        )
    } yield ()
  }

  def ensureDomainRegisteredAndConnected(
      config: SynchronizerConnectionConfig,
      overwriteExistingConnection: Boolean,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = for {
    _ <- retryProvider
      .ensureThat(
        retryFor,
        "domain_registered",
        s"participant registered ${config.synchronizerAlias} with config $config",
        lookupSynchronizerConnectionConfig(config.synchronizerAlias).map {
          case Some(_) if !overwriteExistingConnection => Right(())
          // We don't set the sequencer id when connecting but Canton returns it so we ignore it in the comparison here.
          case Some(existingConfig)
              if ParticipantAdminConnection.dropSequencerId(
                existingConfig
              ) == ParticipantAdminConnection.dropSequencerId(config) =>
            Right(())
          case Some(other) => Left(Some(other))
          case None => Left(None)
        },
        (existingDomainConfig: Option[SynchronizerConnectionConfig]) =>
          existingDomainConfig match {
            case None =>
              logger.info(s"Registering new domain with config $config")
              registerDomain(config, handshakeOnly = false)
            case Some(_) =>
              modifySynchronizerConnectionConfigAndReconnect(
                config.synchronizerAlias,
                _ => Some(config),
              )
                .map(_ => ())
          },
        logger,
      )
    _ <- connectDomain(config.synchronizerAlias)
  } yield ()

  private def reconnectDomain(alias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    _ <- retryProvider.retryForClientCalls(
      "reconnect_domain_disconnect",
      s"participant is disconnected from $alias",
      disconnectDomain(alias),
      logger,
    )
    _ <- connectDomain(alias)
  } yield ()

  def getParticipantTrafficState(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext): Future[TrafficState] = {
    runCmd(
      ParticipantAdminCommands.TrafficControl.GetTrafficControlState(synchronizerId)
    )
  }

  def downloadAcsSnapshot(
      parties: Set[PartyId],
      filterSynchronizerId: Option[SynchronizerId] = None,
      timestamp: Option[Instant] = None,
      force: Boolean = false,
  )(implicit traceContext: TraceContext): Future[ByteString] = {
    logger.debug(
      show"Downloading ACS snapshot from domain $filterSynchronizerId, for parties $parties at timestamp $timestamp"
    )
    val requestComplete = Promise[ByteString]()
    // TODO(DACH-NY/canton-network-node#3298) just concatenate the byteString here. Make it scale to 2M contracts.
    val observer = new GrpcByteChunksToByteArrayObserver[ExportAcsOldResponse](requestComplete)
    runCmd(
      ParticipantAdminCommands.ParticipantRepairManagement.ExportAcsOld(
        parties = parties,
        partiesOffboarding = false,
        filterSynchronizerId,
        timestamp,
        observer,
        force,
      )
    ).discard
    requestComplete.future
  }

  def uploadAcsSnapshot(acsBytes: ByteString)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    retryProvider.retryForClientCalls(
      "import_acs",
      "Imports the acs in the participantl",
      runCmd(
        ParticipantAdminCommands.ParticipantRepairManagement
          .ImportAcsOld(
            acsBytes,
            IMPORT_ACS_WORKFLOW_ID_PREFIX,
            allowContractIdSuffixRecomputation = false,
          ),
        timeoutOverride = Some(GrpcAdminCommand.DefaultUnboundedTimeout),
      ).map(_ => ()),
      logger,
    )
  }

  def getParticipantId()(implicit traceContext: TraceContext): Future[ParticipantId] =
    getId().map(ParticipantId(_))

  def lookupSynchronizerConnectionConfig(
      domain: SynchronizerAlias
  )(implicit traceContext: TraceContext): Future[Option[SynchronizerConnectionConfig]] =
    for {
      configuredDomains <- runCmd(
        ParticipantAdminCommands.SynchronizerConnectivity.ListRegisteredSynchronizers
      )
    } yield configuredDomains
      .collectFirst {
        case (configuredDomain, _, _) if configuredDomain.synchronizerAlias == domain =>
          configuredDomain
      }

  def getSynchronizerConnectionConfig(
      domain: SynchronizerAlias
  )(implicit traceContext: TraceContext): Future[SynchronizerConnectionConfig] =
    lookupSynchronizerConnectionConfig(domain).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"Domain $domain is not configured on the participant")
          .asRuntimeException()
      )
    )

  private def setSynchronizerConnectionConfig(config: SynchronizerConnectionConfig)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity.ModifySynchronizerConnection(
        None,
        config,
        SequencerConnectionValidation.ThresholdActive,
      )
    )

  def modifySynchronizerConnectionConfig(
      synchronizer: SynchronizerAlias,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    retryProvider.retryForClientCalls(
      "modify_synchronizer_connection",
      "Set the new synchronizer connection if required",
      for {
        oldConfig <- getSynchronizerConnectionConfig(synchronizer)
        newConfig = f(oldConfig)
        configModified <- newConfig match {
          case None =>
            logger.trace("No update to synchronizer connection config required")
            Future.successful(false)
          case Some(config) =>
            logger.info(
              s"Updating to new synchronizer connection config for synchronizer $synchronizer. Old config: $oldConfig, new config: $config"
            )
            for {
              _ <- setSynchronizerConnectionConfig(config)
            } yield true
        }
      } yield configModified,
      logger,
    )
  }

  private def modifyOrRegisterSynchronizerConnectionConfig(
      config: SynchronizerConnectionConfig,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      configO <- lookupSynchronizerConnectionConfig(config.synchronizerAlias)
      needsReconnect <- configO match {
        case Some(config) =>
          modifySynchronizerConnectionConfig(
            config.synchronizerAlias,
            f,
          )
        case None =>
          logger.info(s"Domain ${config.synchronizerAlias} is new, registering")
          ensureDomainRegisteredAndConnected(
            config,
            overwriteExistingConnection = true,
            retryFor = retryFor,
          ).map(_ => false)
      }
    } yield needsReconnect

  def modifySynchronizerConnectionConfigAndReconnect(
      domain: SynchronizerAlias,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      configModified <- modifySynchronizerConnectionConfig(domain, f)
      _ <-
        if (configModified) {
          logger.info(
            s"reconnect to the domain $domain for new sequencer configuration to take effect"
          )
          reconnectDomain(domain)
        } else Future.unit
    } yield ()

  def modifyOrRegisterSynchronizerConnectionConfigAndReconnect(
      config: SynchronizerConnectionConfig,
      f: SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      configModified <- modifyOrRegisterSynchronizerConnectionConfig(config, f, retryFor)
      _ <-
        if (configModified) {
          logger.info(
            s"reconnect to the domain ${config.synchronizerAlias} for new sequencer configuration to take effect"
          )
          reconnectDomain(config.synchronizerAlias)
        } else Future.unit
    } yield ()

  def ensureInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "initial_party_to_participant",
        show"Party $partyId is allocated on $participantId",
        listPartyToParticipant(
          store.some,
          filterParty = partyId.filterString,
          operation = None,
        ).map(_.nonEmpty),
        proposeInitialPartyToParticipant(
          store,
          partyId,
          Seq(participantId),
        ).map(_ => ()),
        logger,
      )
    } yield ()

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] =
    getParticipantId()

  private def proposeInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participants: Seq[ParticipantId],
      isProposal: Boolean = false,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, PartyToParticipant]] = {
    val hostingParticipants = participants.map(
      HostingParticipant(
        _,
        ParticipantPermission.Submission,
      )
    )
    proposeMapping(
      store,
      PartyToParticipant.tryCreate(
        partyId,
        Thresholds.partyToParticipantThreshold(hostingParticipants),
        hostingParticipants,
      ),
      serial = PositiveInt.one,
      isProposal = isProposal,
    )
  }

  def ensurePartyToParticipantRemovalProposal(
      synchronizerId: SynchronizerId,
      party: PartyId,
      participantToRemove: ParticipantId,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[PartyToParticipant]] = {
    def removeParticipant(participants: Seq[HostingParticipant]): Seq[HostingParticipant] = {
      participants.filterNot(_.participantId == participantToRemove)
    }
    ensurePartyToParticipantProposal(
      s"Party $party is proposed to be removed from $participantToRemove",
      synchronizerId,
      party,
      removeParticipant,
    )
  }

  def ensurePartyToParticipantAdditionProposal(
      synchronizerId: SynchronizerId,
      party: PartyId,
      newParticipant: ParticipantId,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    def addParticipant(participants: Seq[HostingParticipant]): Seq[HostingParticipant] = {
      // New participants are only given Observation rights. We explicitly promote them to Submission rights later.
      // See SvOnboardingPromoteToSubmitterTrigger.
      val newHostingParticipant =
        HostingParticipant(newParticipant, ParticipantPermission.Observation)
      if (participants.map(_.participantId).contains(newHostingParticipant.participantId)) {
        participants
      } else {
        participants.appended(newHostingParticipant)
      }
    }
    ensurePartyToParticipantProposal(
      s"Party $party is proposed to be added on $newParticipant",
      synchronizerId,
      party,
      addParticipant,
    )
  }

  def ensurePartyToParticipantAdditionProposalWithSerial(
      synchronizerId: SynchronizerId,
      party: PartyId,
      newParticipant: ParticipantId,
      expectedSerial: PositiveInt,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    ensureTopologyMapping[PartyToParticipant](
      TopologyStoreId.Synchronizer(synchronizerId),
      show"Party $party is authorized on $newParticipant",
      topologyTransactionType =>
        EitherT(
          getPartyToParticipant(
            synchronizerId = synchronizerId,
            partyId = party,
            topologyTransactionType = topologyTransactionType,
          )
            .map(result =>
              Either
                .cond(
                  result.mapping.participants
                    .exists(hosting => hosting.participantId == newParticipant),
                  result,
                  result,
                )
            )
        ),
      previous => {
        val newHostingParticipants = previous.participants.appended(
          HostingParticipant(
            newParticipant,
            ParticipantPermission.Observation,
          )
        )
        Right(
          PartyToParticipant.tryCreate(
            previous.partyId,
            participants = newHostingParticipants,
            threshold = Thresholds
              .partyToParticipantThreshold(newHostingParticipants),
          )
        )
      },
      RetryFor.ClientCalls,
      isProposal = true,
      recreateOnAuthorizedStateChange = RecreateOnAuthorizedStateChange.Abort(expectedSerial),
    )
  }

  // the participantChange participant sequence must be ordered, if not canton will consider topology proposals with different ordering as fully different proposals and will not aggregate signatures
  private def ensurePartyToParticipantProposal(
      description: String,
      synchronizerId: SynchronizerId,
      party: PartyId,
      participantChange: Seq[HostingParticipant] => Seq[
        HostingParticipant
      ], // participantChange must be idempotent
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    ensureTopologyMapping[PartyToParticipant](
      TopologyStoreId.Synchronizer(synchronizerId),
      description,
      queryType =>
        EitherT(
          getPartyToParticipant(synchronizerId, party, None, queryType)
            .map { result =>
              val newHostingParticipants = participantChange(result.mapping.participants)
              Either.cond(
                result.mapping.participants == newHostingParticipants && result.mapping.threshold == Thresholds
                  .partyToParticipantThreshold(
                    newHostingParticipants
                  ),
                result,
                result,
              )
            }
        ),
      previous => {
        val newHostingParticipants = participantChange(previous.participants)
        Right(
          PartyToParticipant.tryCreate(
            previous.partyId,
            participants = newHostingParticipants,
            threshold = Thresholds.partyToParticipantThreshold(newHostingParticipants),
          )
        )
      },
      RetryFor.WaitingOnInitDependency,
      isProposal = true,
      waitForAuthorization = false,
    )
  }

  def ensureHostingParticipantIsPromotedToSubmitter(
      synchronizerId: SynchronizerId,
      party: PartyId,
      participantId: ParticipantId,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    def promoteParticipantToSubmitter(
        participants: Seq[HostingParticipant]
    ): Seq[HostingParticipant] = {
      val newValue = HostingParticipant(participantId, ParticipantPermission.Submission)
      val oldIndex = participants.indexWhere(_.participantId == newValue.participantId)
      participants.updated(oldIndex, newValue)
    }

    ensureTopologyMapping[PartyToParticipant](
      TopologyStoreId.Synchronizer(synchronizerId),
      s"Participant $participantId is promoted to have Submission permission for party $party",
      topologyTransactionType =>
        EitherT(
          getPartyToParticipant(
            synchronizerId,
            party,
            topologyTransactionType = topologyTransactionType,
          ).map(result => {
            Either.cond(
              result.mapping.participants
                .contains(HostingParticipant(participantId, ParticipantPermission.Submission)),
              result,
              result,
            )
          })
        ),
      previous => {
        Either.cond(
          previous.participants.exists(_.participantId == participantId), {
            val newHostingParticipants = promoteParticipantToSubmitter(previous.participants)
            PartyToParticipant.tryCreate(
              previous.partyId,
              participants = newHostingParticipants,
              threshold = Thresholds.partyToParticipantThreshold(newHostingParticipants),
            )
          },
          show"Participant $participantId does not host party $party",
        )
      },
      retryFor,
      isProposal = true,
    )
  }

  private def setPruningSchedule(
      cron: String,
      maxDuration: PositiveDurationSeconds,
      retention: PositiveDurationSeconds,
  )(implicit tc: TraceContext): Future[Unit] =
    runCmd(pruningCommands.SetScheduleCommand(cron, maxDuration, retention))

  private def getPruningSchedule()(implicit tc: TraceContext): Future[Option[PruningSchedule]] =
    runCmd(pruningCommands.GetScheduleCommand())

  /** The schedule is specified in cron format and "max_duration" and "retention" durations. The cron string indicates
    *      the points in time at which pruning should begin in the GMT time zone, and the maximum duration indicates how
    *      long from the start time pruning is allowed to run as long as pruning has not finished pruning up to the
    *      specified retention period.
    */
  def ensurePruningSchedule(
      cron: String,
      maxDuration: PositiveDurationSeconds,
      retention: PositiveDurationSeconds,
  )(implicit tc: TraceContext): Future[Unit] =
    retryProvider.ensureThatB(
      RetryFor.WaitingOnInitDependency,
      "participant_pruning_schedule",
      s"Pruning schedule is set to ($cron, $maxDuration, $retention)",
      getPruningSchedule().map(scheduleO =>
        scheduleO.contains(PruningSchedule(cron, maxDuration, retention))
      ),
      setPruningSchedule(cron, maxDuration, retention),
      logger,
    )

}

object ParticipantAdminConnection {
  import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
  import com.digitalasset.canton.admin.participant.v30.{SynchronizerConnectionConfig as _, *}
  import com.digitalasset.canton.admin.participant.v30.PackageServiceGrpc.PackageServiceStub
  import io.grpc.ManagedChannel

  final val IMPORT_ACS_WORKFLOW_ID_PREFIX = "canton-network-acs-import"

  // The Canton APIs insist on writing the bytestring to a file so we define
  // our own variant.
  final case class LookupDarByteString(
      mainPackageId: String
  )(implicit ec: ExecutionContext)
      extends GrpcAdminCommand[GetDarRequest, Option[GetDarResponse], Option[ByteString]] {
    override type Svc = PackageServiceStub

    override def createService(channel: ManagedChannel): PackageServiceStub =
      PackageServiceGrpc.stub(channel)

    override def createRequest(): Either[String, GetDarRequest] =
      Right(GetDarRequest(mainPackageId))

    override def submitRequest(
        service: PackageServiceStub,
        request: GetDarRequest,
    ): Future[Option[GetDarResponse]] =
      service.getDar(request).map(Some(_)).recover {
        case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND => None
      }

    override def handleResponse(
        response: Option[GetDarResponse]
    ): Either[String, Option[ByteString]] =
      // For some reason the API does not throw a NOT_FOUND but instead returns
      // a successful response with data set to an empty bytestring.
      // To make things extra fun, this is inconsistent. Other APIs on the package service
      // do return NOT_FOUND.
      Right(response.map(_.payload))

    // might be a big file to download
    override def timeoutType
        : com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand.DefaultUnboundedTimeout.type =
      GrpcAdminCommand.DefaultUnboundedTimeout

  }

  /** Like [[ParticipantAdminConnection]], but document that the scope is only
    * interested in the `getParticipantId` feature.
    */
  sealed trait HasParticipantId {
    def getParticipantId()(implicit traceContext: TraceContext): Future[ParticipantId]
  }

  object HasParticipantId {
    @com.google.common.annotations.VisibleForTesting
    private[splice] def Const(participantId: ParticipantId): HasParticipantId =
      new HasParticipantId {
        override def getParticipantId()(implicit
            traceContext: TraceContext
        ): Future[ParticipantId] =
          Future successful participantId
      }

    /** For tests that don't care about the random separation provided by the
      * participant ID in the hash.
      */
    @com.google.common.annotations.VisibleForTesting
    private[splice] val ForTesting = Const(ParticipantId("OnlyForTesting"))
  }

  def dropSequencerId(config: SynchronizerConnectionConfig): SynchronizerConnectionConfig =
    config.copy(
      sequencerConnections = dropSequencerId(config.sequencerConnections)
    )

  def dropSequencerId(connections: SequencerConnections): SequencerConnections = {
    connections.connections.foldLeft(connections) { case (acc, c) =>
      acc.modify(c.sequencerAlias, dropSequencerId)
    }
  }

  def dropSequencerId(connection: SequencerConnection): SequencerConnection = connection match {
    case grpc: GrpcSequencerConnection => grpc.copy(sequencerId = None)
    case _ => connection
  }
}
