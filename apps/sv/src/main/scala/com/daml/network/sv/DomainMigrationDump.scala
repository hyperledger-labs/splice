package com.daml.network.sv

import com.daml.network.sv.DomainMigrationDump.{Dar, DomainMigrationDumpNodeIdentities}
import com.daml.network.http.v0.definitions as http
import cats.syntax.either.*
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.network.util.Codec
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.protocol.v30.TopologyTransactions
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.{DomainId, MediatorId, ParticipantId, PartyId, SequencerId}
import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax.*

import java.util.Base64

case class DomainMigrationDump(
    svPartyId: PartyId,
    svcPartyId: PartyId,
    domainAlias: DomainAlias,
    domainId: DomainId,
    nodeIdentities: DomainMigrationDumpNodeIdentities,
    topologySnapshot: GenericStoredTopologyTransactionsX,
    acsSnapshot: ByteString,
    dars: Seq[Dar],
) {
  def toHttp: http.GetDomainMigrationDumpResponse = http.GetDomainMigrationDumpResponse(
    svPartyId.toProtoPrimitive,
    svcPartyId.toProtoPrimitive,
    domainAlias.toProtoPrimitive,
    domainId.toProtoPrimitive,
    http.DomainMigrationIdentities(
      nodeIdentities.participant.toHttp,
      nodeIdentities.sequencer.toHttp,
      nodeIdentities.mediator.toHttp,
    ),
    Base64.getEncoder.encodeToString(topologySnapshot.toProtoV30.toByteArray),
    Base64.getEncoder.encodeToString(acsSnapshot.toByteArray),
    dars.map { dar =>
      val content = Base64.getEncoder.encodeToString(dar.content.toByteArray)
      http.Dar(dar.hash.toHexString, content)
    }.toVector,
  )

  def toJson: Json = {
    toHttp.asJson
  }
}

object DomainMigrationDump {
  def fromHttp(
      response: http.GetDomainMigrationDumpResponse
  ): Either[String, DomainMigrationDump] = for {
    svPartyId <- Codec.decode(Codec.Party)(response.svPartyId)
    svcPartyId <- Codec.decode(Codec.Party)(response.svcPartyId)
    domainAlias <- DomainAlias.create(response.domainAlias)
    domainId <- Codec.decode(Codec.DomainId)(response.domainId)
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
        .fromProtoV30(proto)
        .leftMap(_ => "Failed to parse Topology Transactions")
    }
    acsSnapshot = {
      val decoded = Base64.getDecoder().decode(response.acsSnapshot)
      ByteString.copyFrom(decoded)
    }
    dars = {
      response.dars.map { dar =>
        val decoded = Base64.getDecoder().decode(dar.content)
        Dar(Hash.tryFromHexString(dar.hash), ByteString.copyFrom(decoded))
      }
    }
  } yield DomainMigrationDump(
    svPartyId,
    svcPartyId,
    domainAlias,
    domainId,
    nodeIdentities = DomainMigrationDumpNodeIdentities(
      participantIdentities,
      sequencerIdentities,
      mediatorIdentities,
    ),
    topologySnapshot = topologySnapshot,
    acsSnapshot = acsSnapshot,
    dars = dars,
  )

  final case class DomainMigrationDumpNodeIdentities(
      participant: NodeIdentitiesDump,
      sequencer: NodeIdentitiesDump,
      mediator: NodeIdentitiesDump,
  )

  final case class Dar(hash: Hash, content: ByteString)

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
