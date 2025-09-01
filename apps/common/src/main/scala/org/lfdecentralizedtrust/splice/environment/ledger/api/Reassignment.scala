// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment.ledger.api

import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyInstances, PrettyPrinting}
import com.daml.ledger.api.v2.reassignment as multidomain
import com.digitalasset.canton.data.CantonTimestamp

final case class Reassignment[+E](
    updateId: String,
    offset: Long,
    recordTime: CantonTimestamp,
    workflowId: String,
    events: Seq[ReassignmentEvent],
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("updateId", (x: this.type) => x.updateId)(PrettyInstances.prettyString),
      param("offset", _.offset),
      param("workflowId", (x: this.type) => x.workflowId)(PrettyInstances.prettyString),
      param("events", _.events),
    )

  val synchronizerId = events match {
    case first +: others =>
      others.foreach { e =>
        if (first.sequencedOn != e.sequencedOn) {
          throw new IllegalStateException(s"Reassignment with inconsistent synchronizers: $this")
        }
      }
      first.sequencedOn
    case _ => throw new IllegalStateException(s"Reassignment has no events: $this")

  }
}

object Reassignment {
  private[splice] def fromProto(
      proto: multidomain.Reassignment
  ): Reassignment[ReassignmentEvent] = {
    val events = proto.events.map {
      _.event match {
        case multidomain.ReassignmentEvent.Event.Unassigned(out) =>
          ReassignmentEvent.Unassign.fromProto(out)
        case multidomain.ReassignmentEvent.Event.Assigned(in) =>
          ReassignmentEvent.Assign.fromProto(in)
        case multidomain.ReassignmentEvent.Event.Empty =>
          throw new IllegalArgumentException("uninitialized transfer event")
      }
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
      proto.offset,
      recordTime,
      proto.workflowId,
      events,
    )
  }
}
