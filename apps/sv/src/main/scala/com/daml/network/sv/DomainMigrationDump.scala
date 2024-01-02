package com.daml.network.sv

import com.daml.network.sv.DomainMigrationDump.DomainMigrationDumpNodeIdentities
import com.daml.network.http.v0.definitions as http
import cats.syntax.either.*
import com.daml.network.identities.NodeIdentitiesDump
import com.digitalasset.canton.protocol.v0.TopologyTransactions
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.{MediatorId, ParticipantId, SequencerId}
import com.google.protobuf.ByteString

import java.util.Base64

case class DomainMigrationDump(
    nodeIdentities: DomainMigrationDumpNodeIdentities,
    topologySnapshot: GenericStoredTopologyTransactionsX,
    acsSnapshot: ByteString,
) {}

object DomainMigrationDump {
  def fromHttp(
      response: http.GetDomainMigrationDumpResponse
  ): Either[String, DomainMigrationDump] = for {
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
    topologySnapshot <- {
      val decoded = Base64.getDecoder().decode(response.topologySnapshot)
      val proto = TopologyTransactions.parseFrom(decoded)
      StoredTopologyTransactionsX
        .fromProtoV0(proto)
        .leftMap(_ => "Failed to parse Topology Transactions")
    }
    acsSnapshot = {
      val decoded = Base64.getDecoder().decode(response.acsSnapshot)
      ByteString.copyFrom(decoded)
    }
  } yield DomainMigrationDump(
    nodeIdentities = DomainMigrationDumpNodeIdentities(
      participantIdentities,
      sequencerIdentities,
      mediatorIdentities,
    ),
    topologySnapshot = topologySnapshot,
    acsSnapshot = acsSnapshot,
  )

  final case class DomainMigrationDumpNodeIdentities(
      participant: NodeIdentitiesDump,
      sequencer: NodeIdentitiesDump,
      mediator: NodeIdentitiesDump,
  )

  private def tryFromSequencerIdProtoPrimitive(sequencerId: String) = SequencerId
    .fromProtoPrimitive(sequencerId, "sequencerId")
    .fold(
      err => throw new IllegalArgumentException(err.message),
      identity,
    )

  private def tryFromMediatorIdProtoPrimitive(mediatorId: String) = MediatorId
    .fromProtoPrimitive(mediatorId, "mediatorId")
    .fold(
      err => throw new IllegalArgumentException(err.message),
      identity,
    )
}
