// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.ledger.javaapi.data.{ArchivedEvent, CreatedEvent, ExercisedEvent, Transaction}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequestOutcome as VRO
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_Accepted

import java.time.Instant
import scala.jdk.CollectionConverters.*

sealed trait VoteRequestOutcome {
  val effectiveAt: Option[Instant]
}
case class Accepted(effectiveAt: Option[Instant]) extends VoteRequestOutcome
case class Rejected(effectiveAt: Option[Instant]) extends VoteRequestOutcome

object VoteRequestOutcome {
  def parse(outcome: VRO): VoteRequestOutcome = {
    outcome match {
      case request: VRO_Accepted =>
        Accepted(Some(request.effectiveAt))
      case _ => Rejected(None)
    }
  }
}

final case class IngestedEvents(numCreatedEvents: Long, numExercisedEvents: Long)
object IngestedEvents {
  def eventCount(txs: Iterable[Transaction]): IngestedEvents =
    txs
      .foldLeft(IngestedEvents(0L, 0L)) { case (acc, next) =>
        next.getEventsById.asScala.foldLeft(acc) {
          case (acc, (_, _: ArchivedEvent | _: ExercisedEvent)) =>
            acc.copy(numExercisedEvents = acc.numExercisedEvents + 1)
          case (acc, (_, _: CreatedEvent)) =>
            acc.copy(numCreatedEvents = acc.numCreatedEvents + 1)
          case (_, (_, e)) =>
            throw new IllegalArgumentException(s"Unrecognized event type: $e")
        }
      }
}
