package com.daml.network.environment.ledger.api

import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyInstances, PrettyPrinting}
import com.digitalasset.canton.research.participant.multidomain.transfer as xfr

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
  private[api] def fromProto(proto: xfr.Transfer): Transfer[TransferEvent] = {
    val offset = new LedgerOffset.Absolute(proto.offset)
    val event = proto.event match {
      case xfr.Transfer.Event.TransferOutEvent(out) => TransferEvent.Out.fromProto(out)
      case xfr.Transfer.Event.TransferInEvent(in) => TransferEvent.In.fromProto(in)
      case xfr.Transfer.Event.Empty =>
        throw new IllegalArgumentException("uninitialized transfer event")
    }
    Transfer(
      proto.updateId,
      offset,
      event,
    )
  }
}
