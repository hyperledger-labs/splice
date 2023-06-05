package com.daml.network.environment.ledger.api

import com.daml.ledger.api.v1.event as scalaEvent
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.protocol.v0.multidomain
import com.digitalasset.canton.topology.DomainId

final case class ActiveContract(
    domainId: DomainId,
    createdEvent: CreatedEvent,
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("domainId", _.domainId),
      param("createdEvent", _.createdEvent),
    )
}

object ActiveContract {
  def fromProto(proto: multidomain.ActiveContract): ActiveContract = {
    ActiveContract(
      DomainId.tryFromString(proto.domainId),
      CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
    )
  }
}
