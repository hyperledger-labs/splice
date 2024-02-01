package com.daml.network.sv

import com.daml.network.environment.{ParticipantAdminConnection, TopologyAdminConnection}
import com.daml.network.http.v0.definitions as http
import com.daml.network.identities.{NodeIdentitiesDump, NodeIdentitiesStore}
import com.daml.network.sv.DomainNodeIdentitiesDump.DomainNodeIdentities
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{MediatorId, ParticipantId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import io.circe.syntax.*

import scala.concurrent.{ExecutionContext, Future}

case class DomainNodeIdentitiesDump(
    nodeIdentities: DomainNodeIdentities
) {
  def toHttp: http.GetDomainNodeIdentitiesDumpResponse = http.GetDomainNodeIdentitiesDumpResponse(
    http.DomainNodeIdentities(
      nodeIdentities.participant.toHttp,
      nodeIdentities.sequencer.toHttp,
      nodeIdentities.mediator.toHttp,
    )
  )

  def toJson: Json = {
    toHttp.asJson
  }
}

object DomainNodeIdentitiesDump {
  def fromHttp(
      response: http.GetDomainNodeIdentitiesDumpResponse
  ): Either[String, DomainNodeIdentitiesDump] = for {
    participantIdentities <- NodeIdentitiesDump.fromHttp(
      ParticipantId.tryFromProtoPrimitive,
      response.identities.participant,
    )
    sequencerIdentities <- NodeIdentitiesDump.fromHttp(
      tryFromSequencerIdProtoPrimitive,
      response.identities.sequencer,
    )
    mediatorIdentities <- NodeIdentitiesDump.fromHttp(
      tryFromMediatorIdProtoPrimitive,
      response.identities.mediator,
    )
  } yield DomainNodeIdentitiesDump(
    nodeIdentities = DomainNodeIdentities(
      participantIdentities,
      sequencerIdentities,
      mediatorIdentities,
    )
  )

  def getDomainNodeIdentitiesDump(
      participantAdminConnection: ParticipantAdminConnection,
      domainNode: LocalDomainNode,
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[DomainNodeIdentities] = {
    def newNodeIdentitiesStore(adminConnection: TopologyAdminConnection) =
      new NodeIdentitiesStore(
        adminConnection,
        None,
        clock,
        loggerFactory,
      )

    for {
      participantIdentities <- newNodeIdentitiesStore(participantAdminConnection)
        .getNodeIdentitiesDump()
      sequencerIdentities <- newNodeIdentitiesStore(domainNode.sequencerAdminConnection)
        .getNodeIdentitiesDump()
      mediatorIdentities <- newNodeIdentitiesStore(domainNode.mediatorAdminConnection)
        .getNodeIdentitiesDump()
    } yield DomainNodeIdentities(
      participantIdentities,
      sequencerIdentities,
      mediatorIdentities,
    )

  }

  final case class DomainNodeIdentities(
      participant: NodeIdentitiesDump,
      sequencer: NodeIdentitiesDump,
      mediator: NodeIdentitiesDump,
  )

  def tryFromSequencerIdProtoPrimitive(sequencerId: String) = SequencerId
    .fromProtoPrimitive(sequencerId, "sequencerId")
    .fold(
      err => throw new IllegalArgumentException(err.message),
      identity,
    )

  def tryFromMediatorIdProtoPrimitive(mediatorId: String) = MediatorId
    .fromProtoPrimitive(mediatorId, "mediatorId")
    .fold(
      err => throw new IllegalArgumentException(err.message),
      identity,
    )
}
