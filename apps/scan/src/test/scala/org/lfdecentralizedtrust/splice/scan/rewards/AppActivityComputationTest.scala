// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.timestamp.Timestamp as ProtoTimestamp
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future

class AppActivityComputationTest extends AnyWordSpec with BaseTest {

  "AppActivityComputation" should {

    "return empty when there are no featured app confirmers" in {
      val verdict = mkVerdict(Map(0 -> mkView(Seq("partyA"))))
      val summary = mkSummary(100L, Seq(mkEnvelope(100L, Seq(0))))

      val result = computeWith(Set("partyB"), Seq((summary, verdict)))
      result shouldBe empty
    }

    "return empty for rejected verdicts" in {
      val verdict = mkVerdict(
        Map(0 -> mkView(Seq("partyA"))),
        verdictResult = v30.VerdictResult.VERDICT_RESULT_REJECTED,
      )
      val summary = mkSummary(100L, Seq(mkEnvelope(100L, Seq(0))))

      val result = computeWith(Set("partyA"), Seq((summary, verdict)))
      result shouldBe empty
    }

    "attribute all traffic to a single app confirmer" in {
      val verdict = mkVerdict(Map(0 -> mkView(Seq("partyA"))))
      val summary = mkSummary(100L, Seq(mkEnvelope(100L, Seq(0))))

      val result = computeWith(Set("partyA"), Seq((summary, verdict)))
      result should have size 1
      result.head.appProviderParties shouldBe Seq("partyA")
      // per_app_weight = (100 * 100) / (100 * 1) = 100
      result.head.appActivityWeights shouldBe Seq(100L)
    }

    "split traffic equally among multiple app confirmers in a single envelope" in {
      val verdict = mkVerdict(Map(0 -> mkView(Seq("partyA", "partyB"))))
      val summary = mkSummary(200L, Seq(mkEnvelope(200L, Seq(0))))

      val result = computeWith(Set("partyA", "partyB"), Seq((summary, verdict)))
      result should have size 1
      result.head.appProviderParties shouldBe Seq("partyA", "partyB")
      result.head.appActivityWeights shouldBe Seq(100L, 100L)
    }

    // This probably cannot happen, nevertheless we can handle it
    "handle zero traffic cost envelope" in {
      val verdict = mkVerdict(Map(0 -> mkView(Seq("partyA"))))
      val summary = mkSummary(
        100L,
        Seq(
          mkEnvelope(0L, Seq(0)),
          mkEnvelope(100L, Seq(0)),
        ),
      )

      val result = computeWith(Set("partyA"), Seq((summary, verdict)))
      result should have size 1
      result.head.appActivityWeights shouldBe Seq(100L)
    }

    "produce one record per verdict in a batch" in {
      val verdict1 = mkVerdict(Map(0 -> mkView(Seq("partyA"))))
      val summary1 = mkSummary(100L, Seq(mkEnvelope(100L, Seq(0))))

      val ts2 = CantonTimestamp.ofEpochSecond(2000)
      val verdict2 = mkVerdict(Map(0 -> mkView(Seq("partyA"))), timestamp = ts2)
      val summary2 = mkSummary(200L, Seq(mkEnvelope(200L, Seq(0))), timestamp = ts2)

      val result =
        computeWith(Set("partyA"), Seq((summary1, verdict1), (summary2, verdict2)))
      result should have size 2
    }

    "produce deterministic party ordering (alphabetical)" in {
      val verdict = mkVerdict(Map(0 -> mkView(Seq("zulu", "alpha", "mike"))))
      val summary = mkSummary(300L, Seq(mkEnvelope(300L, Seq(0))))

      val result = computeWith(Set("zulu", "alpha", "mike"), Seq((summary, verdict)))
      result should have size 1
      result.head.appProviderParties shouldBe Seq("alpha", "mike", "zulu")
    }

    "deduplicate the same party appearing in multiple quorums of one view" in {
      val viewWithDuplicateParty = v30.TransactionView(
        informees = Seq("partyA", "partyB"),
        confirmingParties = Seq(
          v30.Quorum(parties = Seq("partyA"), threshold = 1),
          v30.Quorum(parties = Seq("partyA", "partyB"), threshold = 2),
          v30.Quorum(parties = Seq("partyA", "partyC"), threshold = 2),
        ),
        subViews = Seq.empty,
        viewHash = ByteString.EMPTY,
      )
      val verdict = mkVerdict(Map(0 -> viewWithDuplicateParty))
      val summary = mkSummary(100L, Seq(mkEnvelope(100L, Seq(0))))

      val result = computeWith(Set("partyA", "partyB"), Seq((summary, verdict)))
      result should have size 1
      result.head.appProviderParties shouldBe Seq("partyA", "partyB")
      result.head.appActivityWeights shouldBe Seq(50L, 50L)
    }
  }

  private val ts = CantonTimestamp.ofEpochSecond(1000)

  // DvP settlement example from CIP-104
  // 5 nodes, each with its own view and envelope:
  //   Node 1 (Exercise SimpleDvp.Settle):                  confirmers = {Alice, Bob},   1200 bytes
  //   Node 2 (Exercise SimpleAsset.Transfer EUR to Bob):   confirmers = {Alice, Bank1},  400 bytes
  //   Node 3 (Create SimpleAsset 1 EUR):                   confirmers = {Bank1},          200 bytes
  //   Node 4 (Exercise SimpleAsset.Transfer USD to Alice): confirmers = {Bob, Bank2},    500 bytes
  //   Node 5 (Create SimpleAsset 1 USD):                   confirmers = {Bank2},          200 bytes
  //   total_confirmation_request_traffic = 2500 bytes
  "DvP settlement example" should {

    val dvpViews: Map[Int, v30.TransactionView] = Map(
      0 -> mkView(Seq("Alice", "Bob")), // Node 1: Exercise SimpleDvp.Settle
      1 -> mkView(Seq("Alice", "Bank1")), // Node 2: Exercise SimpleAsset.Transfer EUR to Bob
      2 -> mkView(Seq("Bank1")), // Node 3: Create SimpleAsset 1 EUR
      3 -> mkView(Seq("Bob", "Bank2")), // Node 4: Exercise SimpleAsset.Transfer USD to Alice
      4 -> mkView(Seq("Bank2")), // Node 5: Create SimpleAsset 1 USD
    )
    val dvpVerdict = mkVerdict(dvpViews)
    val dvpSummary = mkSummary(
      2500L,
      Seq(
        mkEnvelope(1200L, Seq(0)), // Node 1: 1200 bytes
        mkEnvelope(400L, Seq(1)), // Node 2: 400 bytes
        mkEnvelope(200L, Seq(2)), // Node 3: 200 bytes
        mkEnvelope(500L, Seq(3)), // Node 4: 500 bytes
        mkEnvelope(200L, Seq(4)), // Node 5: 200 bytes
      ),
    )

    "Example 1: Bank1 is the only app provider" in {
      val result = computeWith(Set("Bank1"), Seq((dvpSummary, dvpVerdict)))
      result should have size 1
      result.head.appProviderParties shouldBe Seq("Bank1")
      result.head.appActivityWeights shouldBe Seq(2500L)
    }

    "Example 2: Bank1 and Bank2 are app providers" in {
      val result = computeWith(Set("Bank1", "Bank2"), Seq((dvpSummary, dvpVerdict)))
      result should have size 1
      result.head.appProviderParties shouldBe Seq("Bank1", "Bank2")
      result.head.appActivityWeights shouldBe Seq(1153L, 1346L)
    }

    "Example 3: Alice, Bank1, and Bank2 are app providers" in {
      val result =
        computeWith(Set("Alice", "Bank1", "Bank2"), Seq((dvpSummary, dvpVerdict)))
      result should have size 1
      result.head.appProviderParties shouldBe Seq("Alice", "Bank1", "Bank2")
      result.head.appActivityWeights shouldBe Seq(1400L, 400L, 700L)
    }
  }

  private val roundOpensAt = CantonTimestamp.ofEpochSecond(0)

  /** Run computation and extract just the AppActivityRecordT results. */
  private def computeWith(
      featured: Set[String],
      input: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)],
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT] = {
    val provider = new RewardsReferenceDataProvider {
      override def lookupActiveOpenMiningRounds(
          recordTimes: Seq[CantonTimestamp]
      )(implicit tc: TraceContext): Future[Map[CantonTimestamp, (Long, CantonTimestamp)]] =
        Future.successful(recordTimes.map(_ -> (0L, roundOpensAt)).toMap)

      override def lookupFeaturedAppPartiesAsOf(
          asOf: CantonTimestamp
      )(implicit tc: TraceContext): Future[Set[String]] =
        Future.successful(featured)
    }
    new AppActivityComputation(provider, loggerFactory)(directExecutionContext)
      .computeActivities(input)(traceContext)
      .futureValue
      .flatMap { case (_, _, recordO) => recordO }
  }

  private def mkVerdict(
      views: Map[Int, v30.TransactionView],
      timestamp: CantonTimestamp = ts,
      verdictResult: v30.VerdictResult = v30.VerdictResult.VERDICT_RESULT_ACCEPTED,
  ): v30.Verdict = {
    val proto = ProtoTimestamp(seconds = timestamp.getEpochSecond)
    v30.Verdict(
      submittingParties = Seq("submitter"),
      submittingParticipantUid = "participant1",
      verdict = verdictResult,
      recordTime = Some(proto),
      finalizationTime = Some(proto),
      mediatorGroup = 0,
      views = v30.Verdict.Views.TransactionViews(
        v30.TransactionViews(views = views, rootViews = views.keys.toSeq)
      ),
      updateId = "update1",
    )
  }

  private def mkView(confirmers: Seq[String]): v30.TransactionView =
    v30.TransactionView(
      informees = confirmers,
      confirmingParties = Seq(v30.Quorum(parties = confirmers, threshold = 1)),
      subViews = Seq.empty,
      viewHash = ByteString.EMPTY,
    )

  private def mkSummary(
      totalTrafficCost: Long,
      envelopes: Seq[DbScanVerdictStore.EnvelopeT],
      timestamp: CantonTimestamp = ts,
  ): DbScanVerdictStore.TrafficSummaryT =
    DbScanVerdictStore.TrafficSummaryT(
      totalTrafficCost = totalTrafficCost,
      envelopeTrafficSummarys = envelopes,
      sequencingTime = timestamp,
    )

  private def mkEnvelope(
      trafficCost: Long,
      viewIds: Seq[Int],
  ): DbScanVerdictStore.EnvelopeT =
    DbScanVerdictStore.EnvelopeT(trafficCost = trafficCost, viewIds = viewIds)

}
