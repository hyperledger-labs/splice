package com.daml.network.environment.ledger.api

import com.daml.ledger.api.v1.event as scalaEvent
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.research.participant.multidomain
import com.digitalasset.canton.topology.{DomainId, PartyId}

case class InFlightTransferOutEvent(
    transferEvent: TransferEvent.Out,
    createdEvent: CreatedEvent,
)

object InFlightTransferOutEvent {
  private[api] def fromProto(
      proto: multidomain.GetInFlightTransfersResponse.InFlightTransferredOutEvent
  ): InFlightTransferOutEvent =
    InFlightTransferOutEvent(
      transferEvent = TransferEvent.Out.fromProto(proto.getEvent),
      createdEvent =
        CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
    )
}

sealed trait TransferEvent extends Product with Serializable with PrettyPrinting {
  def submitter: PartyId

  def source: DomainId

  def target: DomainId
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
    private[api] def fromProto(proto: multidomain.TransferredOutEvent): Out = {
      Out(
        submitter = PartyId.tryFromProtoPrimitive(proto.submitter),
        source = DomainId.tryFromString(proto.source),
        target = DomainId.tryFromString(proto.target),
        transferOutId = proto.transferOutId,
        contractId = new ContractId(proto.contractId),
      )
    }
  }

  final case class In(
      override val submitter: PartyId,
      override val source: DomainId,
      override val target: DomainId,
      transferOutId: String,
      createdEvent: CreatedEvent,
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
    private[api] def fromProto(proto: multidomain.TransferredInEvent): In = {
      import com.daml.ledger.api.v1.event as scalaEvent
      In(
        submitter = PartyId.tryFromProtoPrimitive(proto.submitter),
        source = DomainId.tryFromString(proto.source),
        target = DomainId.tryFromString(proto.target),
        transferOutId = proto.transferOutId,
        createdEvent =
          CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
      )
    }
  }
}
