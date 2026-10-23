// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.votesbackfilling

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.Sink
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ExtActionRequiringConformation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_Expired
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  Reason,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.{
  PeerVoteResult,
  VotesBackfillCursor,
}
import org.lfdecentralizedtrust.splice.scan.store.votesbackfilling.VotesStoreBackfilling.*
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

class VotesStoreBackfillingTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  private def ts(seconds: Int): CantonTimestamp = CantonTimestamp.ofEpochSecond(seconds.toLong)

  private def cursor(seconds: Int, eventNumber: Int = 0): VotesBackfillCursor =
    VotesBackfillCursor(ts(seconds), eventNumber)

  /** The backfilling only passes votes on, so they only differ in what identifies them. */
  private def vote(seconds: Int, eventNumber: Int = 0): PeerVoteResult = {
    val at = ts(seconds).toInstant
    PeerVoteResult(
      ts(seconds),
      "synchronizer",
      s"update-$seconds",
      eventNumber,
      new DsoRules_CloseVoteRequestResult(
        new VoteRequest(
          "requester",
          "dso",
          new ExtActionRequiringConformation(com.daml.ledger.javaapi.data.Unit.getInstance()),
          new Reason("", ""),
          at,
          java.util.Map.of(),
          java.util.Optional.empty(),
          java.util.Optional.empty(),
        ),
        at,
        java.util.List.of(),
        java.util.List.of(),
        new VRO_Expired(com.daml.ledger.javaapi.data.Unit.getInstance()),
      ),
    )
  }

  private class FakeStore(
      firstIngested: Option[CantonTimestamp],
      initialCursor: Option[VotesBackfillCursor] = None,
      initiallyComplete: Boolean = false,
  ) extends VotesBackfillingStore {
    val cursorRef = new AtomicReference(initialCursor)
    val completions = new AtomicInteger(if (initiallyComplete) 1 else 0)
    val inserted = new AtomicReference(Vector.empty[(Seq[PeerVoteResult], VotesBackfillCursor)])

    override def getFirstIngestedRecordTime()(implicit tc: TraceContext) =
      Future.successful(firstIngested)
    override def getBackfillingCursor()(implicit tc: TraceContext) =
      Future.successful(cursorRef.get())
    override def insertPeerVoteResults(rows: Seq[PeerVoteResult], cursor: VotesBackfillCursor)(
        implicit tc: TraceContext
    ) = {
      inserted.updateAndGet(_ :+ (rows -> cursor))
      cursorRef.set(Some(cursor))
      Future.unit
    }
    override def isBackfillingComplete()(implicit tc: TraceContext) =
      Future.successful(completions.get() > 0)
    override def markBackfillingComplete()(implicit tc: TraceContext) = {
      completions.incrementAndGet()
      Future.unit
    }
  }

  /** Answers the requests in order. Fails once the script is used up, like peers that cannot serve yet. */
  private class ScriptedPeers(script: Try[Seq[PeerVoteResult]]*) extends PeerVotesSource {
    private val remaining = new AtomicReference(script.toList)
    val calls = new AtomicReference(Vector.empty[(VotesBackfillCursor, Int)])

    override def votesBefore(cursor: VotesBackfillCursor, count: Int)(implicit
        tc: TraceContext
    ) = {
      calls.updateAndGet(_ :+ (cursor -> count))
      remaining.getAndUpdate(_.drop(1)).headOption match {
        case Some(response) => Future.fromTry(response)
        case None => Future.failed(new IllegalStateException("peers cannot serve yet"))
      }
    }
  }

  private def service(
      store: FakeStore,
      peers: PeerVotesSource,
      isFirstSv: Boolean = false,
      pageSize: Int = 2,
  ) =
    new VotesStoreBackfilling(
      NonNegativeFiniteDuration.ofMillis(10),
      pageSize,
      isFirstSv,
      peers,
      store,
      loggerFactory,
    )

  "VotesStoreBackfilling" should {

    "complete right away on the Scan of the founding SV, without asking peers" in {
      val store = new FakeStore(firstIngested = None)
      val peers = new ScriptedPeers()
      service(store, peers, isFirstSv = true).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(Completed)
        store.completions.get() shouldBe 1
        peers.calls.get() shouldBe empty
      }
    }

    "do nothing once complete, also on the Scan of the founding SV" in {
      val store = new FakeStore(Some(ts(100)), initiallyComplete = true)
      val peers = new ScriptedPeers()
      for {
        regular <- service(store, peers).mksrc().runWith(Sink.seq)
        founding <- service(store, peers, isFirstSv = true).mksrc().runWith(Sink.seq)
      } yield {
        regular shouldBe empty
        founding shouldBe empty
        store.completions.get() shouldBe 1
        peers.calls.get() shouldBe empty
      }
    }

    "wait until the votes store has ingested a transaction" in {
      val store = new FakeStore(firstIngested = None)
      val peers = new ScriptedPeers()
      service(store, peers).mksrc().take(3).runWith(Sink.seq).map { steps =>
        steps shouldBe Seq.fill(3)(WaitingForLiveIngestion)
        store.completions.get() shouldBe 0
        peers.calls.get() shouldBe empty
      }
    }

    "copy pages from the peers, starting at the first ingested record time, until they have nothing older" in {
      val store = new FakeStore(Some(ts(100)))
      val peers = new ScriptedPeers(
        Success(Seq(vote(90), vote(80))),
        Success(Seq(vote(70))),
        Success(Seq.empty),
      )
      service(store, peers, pageSize = 2).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(
          Started(cursor(100)),
          PeerPageBackfilled(2, cursor(80)),
          PeerPageBackfilled(1, cursor(70)),
          Completed,
        )
        peers.calls.get() shouldBe Vector(cursor(100) -> 2, cursor(80) -> 2, cursor(70) -> 2)
        store.inserted.get() shouldBe Vector(
          Seq(vote(90), vote(80)) -> cursor(80),
          Seq(vote(70)) -> cursor(70),
        )
        store.completions.get() shouldBe 1
      }
    }

    "continue before the oldest (record time, event number) of a page" in {
      val store = new FakeStore(Some(ts(100)))
      val peers = new ScriptedPeers(
        Success(Seq(vote(90, eventNumber = 2), vote(90, eventNumber = 1))),
        Success(Seq.empty),
      )
      service(store, peers).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(
          Started(cursor(100)),
          PeerPageBackfilled(2, cursor(90, 1)),
          Completed,
        )
        peers.calls.get().map(_._1) shouldBe Vector(cursor(100), cursor(90, 1))
      }
    }

    "resume from the persisted cursor, not from the first ingested record time" in {
      val store = new FakeStore(Some(ts(100)), initialCursor = Some(cursor(50, 3)))
      val peers = new ScriptedPeers(Success(Seq.empty))
      service(store, peers).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(Resumed(cursor(50, 3)), Completed)
        peers.calls.get().map(_._1) shouldBe Vector(cursor(50, 3))
        store.inserted.get() shouldBe empty
        store.completions.get() shouldBe 1
      }
    }

    "wait and ask again at the same cursor if the peers cannot serve yet" in {
      val store = new FakeStore(Some(ts(100)))
      val peers = new ScriptedPeers(
        Failure(new IllegalStateException("peers are still backfilling")),
        Failure(new IllegalStateException("peers are still backfilling")),
        Success(Seq(vote(90))),
        Success(Seq.empty),
      )
      service(store, peers).mksrc().runWith(Sink.seq).map { steps =>
        steps shouldBe Seq(
          Started(cursor(100)),
          WaitingForPeers(cursor(100)),
          WaitingForPeers(cursor(100)),
          PeerPageBackfilled(1, cursor(90)),
          Completed,
        )
        peers.calls.get().map(_._1) shouldBe
          Vector(cursor(100), cursor(100), cursor(100), cursor(90))
        store.completions.get() shouldBe 1
      }
    }

    "not complete while the peers cannot serve" in {
      val store = new FakeStore(Some(ts(100)))
      service(store, new ScriptedPeers()).mksrc().take(4).runWith(Sink.seq).map { steps =>
        steps shouldBe Started(cursor(100)) +: Seq.fill(3)(WaitingForPeers(cursor(100)))
        store.completions.get() shouldBe 0
        store.inserted.get() shouldBe empty
      }
    }

    "keep the service stream open after completion, so the retrying service does not restart it" in {
      val store = new FakeStore(firstIngested = None)
      service(store, new ScriptedPeers(), isFirstSv = true)
        .serviceSource()
        .completionTimeout(300.millis)
        .runWith(Sink.ignore)
        .failed
        .map { failure =>
          failure shouldBe a[java.util.concurrent.TimeoutException]
          store.completions.get() shouldBe 1
        }
    }
  }
}
