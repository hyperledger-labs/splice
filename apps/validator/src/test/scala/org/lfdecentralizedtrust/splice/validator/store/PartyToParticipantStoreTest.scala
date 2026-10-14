// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.environment.ledger.api.TopologyTransactionUpdate
import org.lfdecentralizedtrust.splice.environment.ledger.api.TopologyTransactionUpdate.*
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

abstract class PartyToParticipantStoreTest
    extends StoreTestBase
    with Matchers
    with HasExecutionContext {

  protected def mkStore(): Future[PartyToParticipantStore]

  private val alice = mkPartyId("alice")
  private val bob = mkPartyId("bob")
  private val p1 = mkParticipantId("p1")
  private val p2 = mkParticipantId("p2")

  private def update(offset: Long, events: TopologyTransactionUpdate.Event*) =
    TopologyTransactionUpdate(offset, dummyDomain, CantonTimestamp.Epoch, events)

  "PartyToParticipantStore" should {

    "be uninitialized initially" in {
      for {
        store <- mkStore()
        offset <- store.lastIngestedOffset()
        hosting <- store.hostingParticipants()
      } yield {
        offset shouldBe None
        hosting shouldBe empty
      }
    }

    "return the initial state" in {
      for {
        store <- mkStore()
        _ <- store.initialize(10, Map(alice -> Seq(p1, p2), bob -> Seq(p1)))
        offset <- store.lastIngestedOffset()
        hosting <- store.hostingParticipants()
      } yield {
        offset shouldBe Some(10)
        hosting shouldBe Map(alice -> Seq(p1, p2), bob -> Seq(p1))
      }
    }

    "reject a second initialization" in {
      for {
        store <- mkStore()
        _ <- store.initialize(10, Map.empty)
        result <- store.initialize(11, Map.empty).failed
      } yield result shouldBe an[IllegalStateException]
    }

    "reject ingestion before initialization" in {
      for {
        store <- mkStore()
        result <- store.ingest(Seq(update(11, ParticipantAuthorizationAdded(alice, p1)))).failed
      } yield result shouldBe an[IllegalStateException]
    }

    "apply added, onboarding, changed and revoked events" in {
      for {
        store <- mkStore()
        _ <- store.initialize(10, Map(alice -> Seq(p1)))
        _ <- store.ingest(
          Seq(
            update(11, ParticipantAuthorizationOnboarding(alice, p2)),
            update(12, ParticipantAuthorizationAdded(bob, p2)),
          )
        )
        afterAdd <- store.hostingParticipants()
        _ <- store.ingest(
          Seq(
            update(
              13,
              ParticipantAuthorizationChanged(alice, p2),
              ParticipantAuthorizationRevoked(alice, p1),
            ),
            update(14, ParticipantAuthorizationRevoked(bob, p2)),
          )
        )
        afterRevoke <- store.hostingParticipants()
        offset <- store.lastIngestedOffset()
      } yield {
        afterAdd shouldBe Map(alice -> Seq(p1, p2), bob -> Seq(p2))
        afterRevoke shouldBe Map(alice -> Seq(p2))
        offset shouldBe Some(14)
      }
    }

    "be idempotent for events already reflected in the initial state" in {
      for {
        store <- mkStore()
        _ <- store.initialize(10, Map(alice -> Seq(p1)))
        _ <- store.ingest(
          Seq(
            update(11, ParticipantAuthorizationAdded(alice, p1)),
            update(12, ParticipantAuthorizationRevoked(bob, p1)),
          )
        )
        hosting <- store.hostingParticipants()
      } yield hosting shouldBe Map(alice -> Seq(p1))
    }
  }
}

class InMemoryPartyToParticipantStoreTest extends PartyToParticipantStoreTest {
  override protected def mkStore(): Future[PartyToParticipantStore] =
    Future.successful(new InMemoryPartyToParticipantStore())
}
