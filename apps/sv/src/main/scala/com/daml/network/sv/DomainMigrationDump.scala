package com.daml.network.sv

import com.daml.network.sv.DomainMigrationDump.DomainMigrationDumpNodeIdentities
import com.daml.network.http.v0.definitions as http
import cats.syntax.traverse.*
import com.daml.network.identities.NodeIdentitiesDump
import com.digitalasset.canton.topology.{MediatorId, ParticipantId, SequencerId}

case class DomainMigrationDump(
    nodeIdentities: DomainMigrationDumpNodeIdentities
) {}

object DomainMigrationDump {
  def fromHttp(
      response: http.GetDomainMigrationDumpResponse
  ): Either[String, DomainMigrationDump] = for {
    participantDump <- NodeIdentitiesDump.fromHttp(
      ParticipantId.tryFromProtoPrimitive,
      response.identities.participant,
    )
    sequencerDump <- response.identities.sequencer.traverse(
      NodeIdentitiesDump.fromHttp(tryFromSequencerIdProtoPrimitive, _)
    )
    mediatorDump <- response.identities.mediator.traverse(
      NodeIdentitiesDump.fromHttp(tryFromMediatorIdProtoPrimitive, _)
    )
  } yield DomainMigrationDump(
    DomainMigrationDumpNodeIdentities(
      participantDump,
      sequencerDump,
      mediatorDump,
    )
  )

  final case class DomainMigrationDumpNodeIdentities(
      participant: NodeIdentitiesDump,
      sequencer: Option[NodeIdentitiesDump],
      mediator: Option[NodeIdentitiesDump],
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
