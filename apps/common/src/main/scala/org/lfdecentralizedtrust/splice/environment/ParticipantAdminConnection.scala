// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.EitherT
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.admin.api.client.commands.{
  GrpcAdminCommand,
  ParticipantAdminCommands,
  PruningSchedulerCommands,
}
import com.digitalasset.canton.admin.api.client.data.{NodeStatus, ParticipantStatus}
import com.digitalasset.canton.admin.participant.v30.PruningServiceGrpc.PruningServiceStub
import com.digitalasset.canton.admin.participant.v30.{
  ExportAcsResponse,
  ExportPartyAcsResponse,
  PruningServiceGrpc,
}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.data.{
  ContractImportMode,
  RepresentativePackageIdOverride,
}
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SequencerConnections,
}
import com.digitalasset.canton.sequencing.protocol.TrafficState
import com.digitalasset.canton.topology.{
  NodeIdentity,
  ParticipantId,
  PartyId,
  PhysicalSynchronizerId,
  SequencerId,
  SynchronizerId,
}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  GrpcConnection,
  HostingParticipant,
  ParticipantPermission,
  PartyToParticipant,
  SignedTopologyTransaction,
  TopologyChangeOp,
}
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
  TopologySnapshot,
}

import java.io.{InputStream, SequenceInputStream}
import java.time.Instant
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

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
    with ParticipantAdminSynchronizerConnection
    with StatusAdminConnection
    with PruningAdminConnection {
  override val serviceName = "Canton Participant Admin API"

  override val pruningCommands: PruningSchedulerCommands[PruningServiceGrpc.PruningServiceStub] =
    new PruningSchedulerCommands[PruningServiceStub](
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

  def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] =
    runCmd(getStatusRequest).map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_, _, _) => false
      case NodeStatus.Success(_) => true
    }

  def offsetByTimestamp(synchronizerId: SynchronizerId, timestamp: Instant, force: Boolean)(implicit
      tc: TraceContext
  ): Future[Long] =
    runCmd(
      ParticipantAdminCommands.PartyManagement
        .GetHighestOffsetByTimestamp(synchronizerId, timestamp, force)
    )

  def downloadAcsSnapshot(
      parties: Set[PartyId],
      synchronizerId: SynchronizerId,
      timestampOrOffset: Either[Instant, Long],
      force: Boolean = false,
  )(implicit traceContext: TraceContext): Future[Seq[ByteString]] = {
    logger.info(
      show"Downloading ACS snapshot from domain $synchronizerId, for parties $parties at $timestampOrOffset"
    )
    val observer = new SeqAccumulatingObserver[ExportAcsResponse]

    for {
      offset <- resolveOffset(timestampOrOffset, synchronizerId, force)
      _ <- runCmd(
        ParticipantAdminCommands.ParticipantRepairManagement.ExportAcs(
          parties = parties,
          filterSynchronizerId = Some(synchronizerId),
          offset,
          observer,
          excludedStakeholders = Set.empty,
          contractSynchronizerRenames = Map.empty,
        )
      )
      responses <- observer.resultFuture
    } yield responses.map(_.chunk)
  }

  private def resolveOffset(
      timestampOrOffset: Either[Instant, Long],
      synchronizerId: SynchronizerId,
      force: Boolean,
  )(implicit tc: TraceContext) = {
    timestampOrOffset match {
      case Right(offset) => Future.successful(offset)
      case Left(timestamp) =>
        offsetByTimestamp(synchronizerId, timestamp, force).map { offset =>
          logger.debug(
            s"Resolved timestamp $timestamp to offset $offset for $synchronizerId, force=$force"
          )
          offset
        }
    }
  }

  def exportPartyAcs(
      party: PartyId,
      synchronizerId: SynchronizerId,
      targetParticipantId: ParticipantId,
      activationTime: Instant,
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[ByteString]] = {
    val observer = new SeqAccumulatingObserver[ExportPartyAcsResponse]

    for {
      // The current ExportPartyAcs requires us to pass an offset that is right before the topology tx
      // in which the participant started hosing `partyId`.
      // Unfortunately, for this code to be fault-tolerant we'd need to store said offset somewhere and then use it.
      // We instead just get the time of the transaction (`activationTime`), resolve its offset,
      // and subtract one so that we have an offset that is guaranteed to be before the activation transaction.
      activationOffset <- resolveOffset(Left(activationTime), synchronizerId, force = true)
      beforeActivationOffset = activationOffset - 1L
      _ = logger.info(
        show"Exporting ACS snapshot for party $party from domain $synchronizerId at offset $beforeActivationOffset"
      )
      _ <- runCmd(
        ParticipantAdminCommands.PartyManagement.ExportPartyAcs(
          party,
          synchronizerId,
          targetParticipantId,
          beforeActivationOffset,
          waitForActivationTimeout = None, // i.e., default
          observer,
        )
      )
      chunks <- observer.resultFuture
    } yield chunks.map(_.chunk)
  }

  def downloadAcsSnapshotNonChunked(
      parties: Set[PartyId],
      filterSynchronizerId: SynchronizerId,
      timestampOrOffset: Either[Instant, Long],
      force: Boolean = false,
  )(implicit traceContext: TraceContext): Future[ByteString] =
    downloadAcsSnapshot(parties, filterSynchronizerId, timestampOrOffset, force).map(chunks =>
      ByteString.copyFrom(chunks.asJava)
    )

  def uploadAcsSnapshot(acsBytes: Seq[ByteString], synchronizerId: SynchronizerId)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    val chunkedAcsBytes: Seq[ByteString] = acsBytes match {
      case Seq(bytes) =>
        // Caller has not chunked the bytes, this is possible for SVs that try to onboard or for validator recovery.
        // The chuning logic here matches what GrpcStreamingUtils.streamToServer does
        bytes.toByteArray.grouped(1024 * 1024 * 2).map(ByteString.copyFrom(_)).toSeq
      case _ => acsBytes
    }
    retryProvider.retryForClientCalls(
      "import_acs",
      "Imports the acs in the participantl",
      runCmd(
        ParticipantAdminCommands.ParticipantRepairManagement
          .ImportAcsBytes(
            chunkedAcsBytes,
            IMPORT_ACS_WORKFLOW_ID_PREFIX,
            contractImportMode = ContractImportMode.Validation,
            excludedStakeholders = Set.empty,
            representativePackageIdOverride = RepresentativePackageIdOverride.NoOverride,
            synchronizerId = synchronizerId,
          ),
        timeoutOverride = Some(GrpcAdminCommand.DefaultUnboundedTimeout),
      ).map(_ => ()),
      logger,
    )
  }

  def importPartyAcs(acsChunks: Seq[ByteString], synchronizerId: SynchronizerId, partyId: PartyId)(
      implicit tc: TraceContext
  ): Future[Unit] = {
    retryProvider.retryForClientCalls(
      "import_party_acs",
      "Imports the acs in the participant",
      runCmd(
        ParticipantAdminCommands.PartyManagement
          .ImportPartyAcs(
            new SequenceInputStream(
              acsChunks.iterator.map(chunk => chunk.newInput(): InputStream).asJavaEnumeration
            ),
            synchronizerId,
            IMPORT_ACS_WORKFLOW_ID_PREFIX,
            contractImportMode = ContractImportMode.Validation,
            representativePackageIdOverride = RepresentativePackageIdOverride.NoOverride,
            // according to docs: enables crash-resilient scheduling of the onboarding flag clearance
            // ...except that only works from protocol version 35+
            party = Some(partyId),
          ),
        timeoutOverride = Some(GrpcAdminCommand.DefaultUnboundedTimeout),
      ).map(_ => ()),
      logger,
    )
  }

  def getParticipantId()(implicit traceContext: TraceContext): Future[ParticipantId] =
    getId().map(ParticipantId(_))

  def getParticipantTrafficState(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext): Future[TrafficState] = {
    runCmd(
      ParticipantAdminCommands.TrafficControl.GetTrafficControlState(synchronizerId)
    )
  }
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
      // onboarding flag is cleared in SvClearOnboardingFlagTrigger
      val newHostingParticipant =
        HostingParticipant(newParticipant, ParticipantPermission.Submission, onboarding = true)
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
      topologySnapshot: TopologySnapshot = TopologySnapshot.Sequenced,
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
            topologySnapshot = topologySnapshot,
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
            ParticipantPermission.Submission,
            onboarding = true,
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
          getPartyToParticipant(synchronizerId, party, None, queryType, TopologySnapshot.Sequenced)
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

  def ensureHostingParticipantIsPromotedToSubmitterAndIsNotOnboarding(
      synchronizerId: SynchronizerId,
      party: PartyId,
      participantId: ParticipantId,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    def promoteParticipantToSubmitter(
        participants: Seq[HostingParticipant]
    ): Seq[HostingParticipant] = {
      val newValue =
        HostingParticipant(participantId, ParticipantPermission.Submission, onboarding = false)
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
            topologySnapshot = TopologySnapshot.Sequenced,
          ).map(result => {
            Either.cond(
              result.mapping.participants
                .contains(
                  HostingParticipant(
                    participantId,
                    ParticipantPermission.Submission,
                    onboarding = false,
                  )
                ),
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

  def performManualLsu(
      currentPsid: PhysicalSynchronizerId,
      successorPsid: PhysicalSynchronizerId,
      upgradeTime: Option[CantonTimestamp],
      sequencerSuccessors: Map[SequencerId, GrpcConnection],
  )(implicit tc: TraceContext): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.SynchronizerConnectivity
        .PerformManualLsu(currentPsid, successorPsid, upgradeTime, Left(sequencerSuccessors))
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
