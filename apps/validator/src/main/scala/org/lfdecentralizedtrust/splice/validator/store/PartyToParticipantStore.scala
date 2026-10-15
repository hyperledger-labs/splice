// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import org.lfdecentralizedtrust.splice.environment.ledger.api.TopologyTransactionUpdate
import org.lfdecentralizedtrust.splice.environment.ledger.api.TopologyTransactionUpdate.*

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.util.Try

trait PartyToParticipantStore {

  def lastIngestedOffset(): Future[Option[Long]]

  def initialize(offset: Long, hostingParticipants: Map[PartyId, Seq[ParticipantId]]): Future[Unit]

  def ingest(updates: Seq[TopologyTransactionUpdate]): Future[Unit]

  def hostingParticipants(): Future[Map[PartyId, Seq[ParticipantId]]]
}

class InMemoryPartyToParticipantStore extends PartyToParticipantStore {
  import InMemoryPartyToParticipantStore.State

  private val state = new AtomicReference[Option[State]](None)

  override def lastIngestedOffset(): Future[Option[Long]] =
    Future.successful(state.get().map(_.offset))

  override def initialize(
      offset: Long,
      hostingParticipants: Map[PartyId, Seq[ParticipantId]],
  ): Future[Unit] =
    if (state.compareAndSet(None, Some(State(offset, hostingParticipants)))) Future.unit
    else Future.failed(new IllegalStateException("PartyToParticipantStore is already initialized"))

  override def ingest(updates: Seq[TopologyTransactionUpdate]): Future[Unit] =
    Future.fromTry(Try {
      state.updateAndGet {
        case None =>
          throw new IllegalStateException("PartyToParticipantStore is not initialized")
        case Some(s) => Some(updates.foldLeft(s)(_.apply(_)))
      }
    }.map(_ => ()))

  override def hostingParticipants(): Future[Map[PartyId, Seq[ParticipantId]]] =
    Future.successful(
      state.get().fold(Map.empty[PartyId, Seq[ParticipantId]])(_.partyToParticipants)
    )
}

object InMemoryPartyToParticipantStore {
  private final case class State(
      offset: Long,
      partyToParticipants: Map[PartyId, Seq[ParticipantId]],
  ) {
    def apply(update: TopologyTransactionUpdate): State =
      State(
        update.offset,
        update.events.foldLeft(partyToParticipants) { (acc, event) =>
          val current = acc.getOrElse(event.partyId, Seq.empty)
          val updated = event match {
            case _: ParticipantAuthorizationAdded | _: ParticipantAuthorizationChanged |
                _: ParticipantAuthorizationOnboarding =>
              if (current.contains(event.participantId)) current
              else current :+ event.participantId
            case _: ParticipantAuthorizationRevoked =>
              current.filterNot(_ == event.participantId)
          }
          if (updated.isEmpty) acc - event.partyId else acc.updated(event.partyId, updated)
        },
      )
  }
}
