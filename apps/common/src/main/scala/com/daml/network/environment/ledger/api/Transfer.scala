package com.daml.network.environment.ledger.api

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyInstances, PrettyPrinting}
import com.daml.ledger.api.v2.reassignment as multidomain

final case class Transfer[+E](
    updateId: String,
    offset: LedgerOffset.Absolute,
    event: E & TransferEvent,
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("updateId", (x: this.type) => x.updateId)(PrettyInstances.prettyString),
      param("offset", (x: this.type) => x.offset.getOffset)(PrettyInstances.prettyString),
      param("event", _.event),
    )
}

object Transfer {
  private[api] def fromProto(proto: multidomain.Reassignment): Transfer[TransferEvent] = {
    val offset = new LedgerOffset.Absolute(proto.offset)
    val event = proto.event match {
      case multidomain.Reassignment.Event.UnassignedEvent(out) => TransferEvent.Out.fromProto(out)
      case multidomain.Reassignment.Event.AssignedEvent(in) => TransferEvent.In.fromProto(in)
      case multidomain.Reassignment.Event.Empty =>
        throw new IllegalArgumentException("uninitialized transfer event")
    }
    Transfer(
      proto.updateId,
      offset,
      event,
    )
  }
}
