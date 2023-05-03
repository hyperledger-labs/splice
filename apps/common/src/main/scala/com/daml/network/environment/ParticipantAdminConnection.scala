package com.daml.network.environment

import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.admin.api.client.commands.{
  ParticipantAdminCommands,
  TopologyAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.{
  ListConnectedDomainsResult,
  ListPartyToParticipantResult,
}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.v0.AcsSnapshotChunk
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.topology.admin.grpc.BaseQuery
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.{
  ParticipantPermission,
  RequestSide,
  TopologyChangeOp,
}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

/** Connection to the subset of the Canton admin API that we rely
  * on in our own applications.
  */
class ParticipantAdminConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(
      config,
      timeouts,
      loggerFactory,
      // The version endpoint is only injected into our own apps so we cannot run this against the admin API.
      enableVersionCompatCheck = false,
    ) {
  override val serviceName = "Canton Participant Admin API"

  private def listConnectedDomains()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedDomainsResult]] = {
    runCmd(ParticipantAdminCommands.DomainConnectivity.ListConnectedDomains())
  }

  def reconnectAllDomains()(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(ParticipantAdminCommands.DomainConnectivity.ReconnectDomains(ignoreFailures = false))
  }

  def disconnectFromAllDomains()(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    domains <- listConnectedDomains()
    _ <- Future.sequence(
      domains.map(domain =>
        runCmd(ParticipantAdminCommands.DomainConnectivity.DisconnectDomain(domain.domainAlias))
      )
    )
  } yield ()

  def authorizePartyToParticipant(
      ops: TopologyChangeOp,
      party: PartyId,
      participant: ParticipantId,
      side: RequestSide,
      permission: ParticipantPermission,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(
      TopologyAdminCommands.Write.AuthorizePartyToParticipant(
        ops,
        None,
        side,
        party,
        participant,
        permission,
        replaceExisting = true,
        force = false,
      )
    ).map(_ => ())
  }

  def listPartyToParticipantMappings(
      filterStore: String = "",
      operation: Option[TopologyChangeOp] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      filterRequestSide: Option[RequestSide] = None,
      filterPermission: Option[ParticipantPermission] = None,
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResult]] = {
    runCmd(
      TopologyAdminCommands.Read.ListPartyToParticipant(
        BaseQuery(
          filterStore,
          useStateStore = true,
          TimeQuery.HeadState,
          operation,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParty,
        filterParticipant,
        filterRequestSide,
        filterPermission,
      )
    )
  }

  def downloadAcsSnapshot(
      parties: Set[PartyId],
      filterDomainId: String = "",
      timestamp: Option[Instant] = None,
      chunkSize: Option[PositiveInt] = None,
  )(implicit traceContext: TraceContext): Future[ByteString] = {
    val requestComplete = Promise[ByteString]()
    // TODO(#3298) just concatenate the byteString here. Make it scale to 2M contracts.
    val observer = new GrpcByteChunksToByteArrayObserver[AcsSnapshotChunk](requestComplete)
    runCmd(
      ParticipantAdminCommands.ParticipantRepairManagement.Download(
        parties,
        filterDomainId,
        timestamp,
        None,
        chunkSize,
        observer,
        gzipFormat = false,
      )
    ).discard
    requestComplete.future
  }

  def uploadAcsSnapshot(acsBytes: ByteString)(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(
      ParticipantAdminCommands.ParticipantRepairManagement.Upload(acsBytes)
    )
  }

  def getParticipantId()(implicit traceContext: TraceContext): Future[Option[ParticipantId]] = {
    runCmd(
      TopologyAdminCommands.Init.GetId()
    ).map(_.map(ParticipantId(_)))
  }
}
