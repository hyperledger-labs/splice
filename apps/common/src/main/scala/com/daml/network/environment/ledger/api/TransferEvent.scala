package com.daml.network.environment.ledger.api

import com.daml.ledger.api.v1.event as scalaEvent
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.daml.ledger.api.v2.reassignment as multidomain
import com.daml.ledger.api.v2.state_service
import com.digitalasset.canton.topology.{DomainId, PartyId}

object IncompleteTransferEvent {
  case class Out(
      transferEvent: TransferEvent.Out,
      createdEvent: CreatedEvent,
  )
  case class In(
      transferEvent: TransferEvent.In
  )
  def fromProto(
      proto: state_service.IncompleteUnassigned
  ): Out =
    Out(
      transferEvent = TransferEvent.Out.fromProto(proto.getUnassignedEvent),
      createdEvent =
        CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
    )

  def fromProto(
      proto: state_service.IncompleteAssigned
  ): In =
    In(
      transferEvent = TransferEvent.In.fromProto(proto.getAssignedEvent)
    )
}

sealed trait TransferEvent extends Product with Serializable with PrettyPrinting {
  def submitter: PartyId

  def source: DomainId

  def target: DomainId

  def counter: Long
}

object TransferEvent {
  private case class TransferOutId(s: String) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfString(_.s)
  }

  final case class Out(
      override val submitter: PartyId,
      override val source: DomainId,
      override val target: DomainId,
      transferOutId: String,
      contractId: ContractId[_],
      override val counter: Long,
  ) extends TransferEvent {
    def pretty: Pretty[this.type] =
      prettyOfClass(
        param("submitter", _.submitter),
        param("source", _.source),
        param("target", _.target),
        param("transferOutId", o => TransferOutId(o.transferOutId)),
        param("contractId", _.contractId),
      )
  }

  object Out {
    private[api] def fromProto(proto: multidomain.UnassignedEvent): Out = {
      Out(
        submitter = PartyId.tryFromProtoPrimitive(proto.submitter),
        source = DomainId.tryFromString(proto.source),
        target = DomainId.tryFromString(proto.target),
        transferOutId = proto.unassignId,
        contractId = new ContractId(proto.contractId),
        counter = proto.reassignmentCounter,
      )
    }
  }

  final case class In(
      override val submitter: PartyId,
      override val source: DomainId,
      override val target: DomainId,
      transferOutId: String,
      createdEvent: CreatedEvent,
      override val counter: Long,
  ) extends TransferEvent {
    def pretty: Pretty[this.type] =
      prettyOfClass(
        param("submitter", _.submitter),
        param("source", _.source),
        param("target", _.target),
        param("transferOutId", i => TransferOutId(i.transferOutId)),
        param("createdEvent", _.createdEvent),
      )
  }

  object In {
    private[api] def fromProto(proto: multidomain.AssignedEvent): In = {
      import com.daml.ledger.api.v1.event as scalaEvent
      In(
        submitter = PartyId.tryFromProtoPrimitive(proto.submitter),
        source = DomainId.tryFromString(proto.source),
        target = DomainId.tryFromString(proto.target),
        transferOutId = proto.unassignId,
        createdEvent =
          CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
        counter = proto.reassignmentCounter,
      )
    }
  }
}
