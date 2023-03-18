package com.daml.network.admin.api.client

import com.digitalasset.canton.admin.api.client.commands.{
  ParticipantAdminCommands,
  TopologyAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.{
  ListConnectedDomainsResult,
  ListPartyToParticipantResult,
}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
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
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton admin API that we rely
  * on in our own applications.
  */
class ParticipantAdminConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(config, timeouts, loggerFactory) {
  override val serviceName = "Canton Participant Admin API"

  def listConnectedDomains()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedDomainsResult]] = {
    runCmd(ParticipantAdminCommands.DomainConnectivity.ListConnectedDomains())
  }

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
  )(implicit traceContext: TraceContext): Future[ByteString] = {
    runCmd(
      ParticipantAdminCommands.PartyMigration
        .DownloadAcsSnapshot(parties, filterDomainId, timestamp, None)
    ).map(_._2)
  }
}
