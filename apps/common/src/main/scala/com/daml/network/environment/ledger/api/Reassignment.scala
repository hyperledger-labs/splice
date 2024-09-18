// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.environment.ledger.api

import com.daml.ledger.javaapi.data.ParticipantOffset
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyInstances, PrettyPrinting}
import com.daml.ledger.api.v2.reassignment as multidomain
import com.digitalasset.canton.data.CantonTimestamp

final case class Reassignment[+E](
    updateId: String,
    offset: ParticipantOffset.Absolute,
    recordTime: CantonTimestamp,
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
  private[network] def fromProto(
      proto: multidomain.Reassignment
  ): Reassignment[ReassignmentEvent] = {
    val offset = new ParticipantOffset.Absolute(proto.offset)
    val event = proto.event match {
      case multidomain.Reassignment.Event.UnassignedEvent(out) =>
        ReassignmentEvent.Unassign.fromProto(out)
      case multidomain.Reassignment.Event.AssignedEvent(in) =>
        ReassignmentEvent.Assign.fromProto(in)
      case multidomain.Reassignment.Event.Empty =>
        throw new IllegalArgumentException("uninitialized transfer event")
    }
    val recordTime = CantonTimestamp
      .fromProtoTimestamp(
        proto.recordTime
          .getOrElse(
            throw new IllegalArgumentException(
              s"transfer event ${proto.updateId} without record time"
            )
          )
      )
      .getOrElse(
        throw new IllegalArgumentException(
          s"transfer event ${proto.updateId} with invalid record time"
        )
      )
    Reassignment(
      proto.updateId,
      offset,
      recordTime,
      event,
    )
  }
}
