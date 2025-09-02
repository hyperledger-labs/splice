// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment.ledger.api

import com.daml.ledger.api.v2.event as scalaEvent
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.daml.ledger.api.v2.reassignment as multidomain
import com.daml.ledger.api.v2.state_service
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.topology.{SynchronizerId, PartyId}

object IncompleteReassignmentEvent {
  case class Unassign(
      reassignmentEvent: ReassignmentEvent.Unassign,
      createdEvent: CreatedEvent,
  )
  case class Assign(
      reassignmentEvent: ReassignmentEvent.Assign
  )
  def fromProto(
      proto: state_service.IncompleteUnassigned
  ): Unassign =
    Unassign(
      reassignmentEvent = ReassignmentEvent.Unassign.fromProto(proto.getUnassignedEvent),
      createdEvent =
        CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
    )

  def fromProto(
      proto: state_service.IncompleteAssigned
  ): Assign =
    Assign(
      reassignmentEvent = ReassignmentEvent.Assign.fromProto(proto.getAssignedEvent)
    )
}

sealed trait ReassignmentEvent extends Product with Serializable with PrettyPrinting {
  def submitter: Option[PartyId]

  def source: SynchronizerId

  def target: SynchronizerId

  def sequencedOn: SynchronizerId

  def counter: Long
}

object ReassignmentEvent {
  private case class UnassignId(s: String) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfString(_.s)
  }

  final case class Unassign(
      override val submitter: Option[PartyId],
      override val source: SynchronizerId,
      override val target: SynchronizerId,
      unassignId: String,
      contractId: ContractId[_],
      override val counter: Long,
  ) extends ReassignmentEvent {

    override def sequencedOn = source

    def pretty: Pretty[this.type] =
      prettyOfClass(
        paramIfNonEmpty("submitter", _.submitter),
        param("source", _.source),
        param("target", _.target),
        param("unassignId", o => UnassignId(o.unassignId)),
        param("contractId", _.contractId),
      )
  }

  object Unassign {
    private[api] def fromProto(proto: multidomain.UnassignedEvent): Unassign = {
      Unassign(
        submitter = Some(proto.submitter).filter(_.nonEmpty).map(PartyId.tryFromProtoPrimitive(_)),
        source = SynchronizerId.tryFromString(proto.source),
        target = SynchronizerId.tryFromString(proto.target),
        unassignId = proto.reassignmentId,
        contractId = new ContractId(proto.contractId),
        counter = proto.reassignmentCounter,
      )
    }
  }

  final case class Assign(
      override val submitter: Option[PartyId],
      override val source: SynchronizerId,
      override val target: SynchronizerId,
      unassignId: String,
      createdEvent: CreatedEvent,
      override val counter: Long,
  ) extends ReassignmentEvent {

    override def sequencedOn = target

    def pretty: Pretty[this.type] =
      prettyOfClass(
        paramIfNonEmpty("submitter", _.submitter),
        param("source", _.source),
        param("target", _.target),
        param("unassignId", i => UnassignId(i.unassignId)),
        param("createdEvent", _.createdEvent),
      )
  }

  object Assign {
    private[api] def fromProto(proto: multidomain.AssignedEvent): Assign = {
      Assign(
        submitter = Some(proto.submitter).filter(_.nonEmpty).map(PartyId.tryFromProtoPrimitive(_)),
        source = SynchronizerId.tryFromString(proto.source),
        target = SynchronizerId.tryFromString(proto.target),
        unassignId = proto.reassignmentId,
        createdEvent =
          CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
        counter = proto.reassignmentCounter,
      )
    }
  }
}
