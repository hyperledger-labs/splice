// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment.ledger.api

import com.daml.ledger.api.v2 as lapi
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId, UniqueIdentifier}

final case class TopologyTransactionUpdate(
    offset: Long,
    synchronizerId: SynchronizerId,
    recordTime: CantonTimestamp,
    events: Seq[TopologyTransactionUpdate.Event],
)

object TopologyTransactionUpdate {

  sealed trait Event {
    def partyId: PartyId
    def participantId: ParticipantId
  }

  final case class ParticipantAuthorizationAdded(partyId: PartyId, participantId: ParticipantId)
      extends Event
  final case class ParticipantAuthorizationChanged(partyId: PartyId, participantId: ParticipantId)
      extends Event
  final case class ParticipantAuthorizationOnboarding(
      partyId: PartyId,
      participantId: ParticipantId,
  ) extends Event
  final case class ParticipantAuthorizationRevoked(partyId: PartyId, participantId: ParticipantId)
      extends Event

  def fromProto(proto: lapi.topology_transaction.TopologyTransaction): TopologyTransactionUpdate = {
    import lapi.topology_transaction.TopologyEvent.Event as E
    def party(p: String) = PartyId.tryFromProtoPrimitive(p)
    def participant(p: String) = ParticipantId(UniqueIdentifier.tryFromProtoPrimitive(p))
    TopologyTransactionUpdate(
      offset = proto.offset,
      synchronizerId = SynchronizerId.tryFromString(proto.synchronizerId),
      recordTime = CantonTimestamp.tryFromProtoTimestamp(
        proto.recordTime.getOrElse(
          throw new IllegalArgumentException(
            s"Topology transaction ${proto.updateId} has no record time"
          )
        )
      ),
      events = proto.events.map(_.event).map {
        case E.ParticipantAuthorizationAdded(e) =>
          ParticipantAuthorizationAdded(party(e.partyId), participant(e.participantId))
        case E.ParticipantAuthorizationChanged(e) =>
          ParticipantAuthorizationChanged(party(e.partyId), participant(e.participantId))
        case E.ParticipantAuthorizationOnboarding(e) =>
          ParticipantAuthorizationOnboarding(party(e.partyId), participant(e.participantId))
        case E.ParticipantAuthorizationRevoked(e) =>
          ParticipantAuthorizationRevoked(party(e.partyId), participant(e.participantId))
        case E.Empty =>
          throw new IllegalArgumentException(
            s"Topology transaction ${proto.updateId} contains an empty event"
          )
      },
    )
  }
}
