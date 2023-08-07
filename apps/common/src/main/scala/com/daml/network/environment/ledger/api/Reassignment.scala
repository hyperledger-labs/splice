package com.daml.network.environment.ledger.api

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyInstances, PrettyPrinting}
import com.daml.ledger.api.v2.reassignment as multidomain

final case class Reassignment[+E](
    updateId: String,
    offset: LedgerOffset.Absolute,
    event: E & ReassignmentEvent,
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("updateId", (x: this.type) => x.updateId)(PrettyInstances.prettyString),
      param("offset", (x: this.type) => x.offset.getOffset)(PrettyInstances.prettyString),
      param("event", _.event),
    )
}

object Reassignment {
  private[api] def fromProto(proto: multidomain.Reassignment): Reassignment[ReassignmentEvent] = {
    val offset = new LedgerOffset.Absolute(proto.offset)
    val event = proto.event match {
      case multidomain.Reassignment.Event.UnassignedEvent(out) =>
        ReassignmentEvent.Unassign.fromProto(out)
      case multidomain.Reassignment.Event.AssignedEvent(in) =>
        ReassignmentEvent.Assign.fromProto(in)
      case multidomain.Reassignment.Event.Empty =>
        throw new IllegalArgumentException("uninitialized transfer event")
    }
    Reassignment(
      proto.updateId,
      offset,
      event,
    )
  }
}
