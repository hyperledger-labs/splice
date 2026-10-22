// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.rewards

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.mediator.admin.v30
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.timestamp.Timestamp as ProtoTimestamp
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppKind
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.PackageConfig
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.scan.store.db.{DbAppActivityRecordStore, DbScanVerdictStore}
import org.lfdecentralizedtrust.splice.util.SpliceUtil
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

    "ignore featured-app weights for user-submitted transactions" in {
      val verdict = mkVerdict(Map(0 -> mkView(Seq("partyA", "partyB"))))
      val summary = mkSummary(200L, Seq(mkEnvelope(200L, Seq(0))))

      val result = computeWithWeights(
        Map("partyA" -> BigDecimal(2.2), "partyB" -> BigDecimal("0.8")),
        Seq((summary, verdict)),
      )
      result should have size 1
      result.head.submittingAppProvider shouldBe None
      result.head.appProviderParties shouldBe Seq("partyA", "partyB")
      result.head.appActivityWeights shouldBe Seq(100L, 100L)
    }

    "attribute the total burn to the featured app that submitted the transaction" in {
      val verdict = mkVerdict(
        Map(0 -> mkView(Seq("partyA", "partyB")), 1 -> mkView(Seq("partyA"))),
        submittingParties = Seq("user", "partyB"),
      )
      val summary =
        mkSummary(200L, Seq(mkEnvelope(150L, Seq(0)), mkEnvelope(50L, Seq(1))))

      val result = computeWith(Set("partyA", "partyB"), Seq((summary, verdict)))
      result should have size 1
      result.head.submittingAppProvider shouldBe Some("partyB")
      // partyA gets no activity
      result.head.appProviderParties shouldBe Seq("partyB")
      result.head.appActivityWeights shouldBe Seq(200L)
      // no featured asset movers in this tx
      result.head.assetActivityProviderParties shouldBe empty
      result.head.assetActivityWeights shouldBe empty
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

    "skip activity record when verdict is submitted by an SV participant" in {
      val verdict = mkVerdict(Map(0 -> mkView(Seq("partyA"))))
      val summary = mkSummary(100L, Seq(mkEnvelope(100L, Seq(0))))

      val result = computeWith(
        featured = Set("partyA"),
        input = Seq((summary, verdict)),
        svParticipantIds = Set(defaultSubmittingParticipant),
      )
      result shouldBe empty
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
  private val defaultSubmittingParticipant = "participant1"
  private val dsoParty = PartyId.tryFromProtoPrimitive("dso::namespace")

  "a transaction submitted by an asset mover" when {

    val verdict = mkVerdict(
      Map(
        0 -> mkView(Seq("mover", "alice")),
        1 -> mkView(Seq("asset1")),
        2 -> mkView(Seq(dsoParty.toProtoPrimitive)),
      ),
      submittingParties = Seq("moverOps"),
    )
    val summary = mkSummary(
      1000L,
      Seq(mkEnvelope(500L, Seq(0)), mkEnvelope(300L, Seq(1)), mkEnvelope(200L, Seq(2))),
    )
    val featuredAppParties = ScanRewardsReferenceStore.FeaturedAppParties(
      providers = Map(assetMover("mover"), asset("asset1")),
      opsPartyProviders = Map("moverOps" -> "mover", "asset1Ops" -> "asset1"),
    )
    // asset1's ops party confirms in place of asset1
    val verdictWithAssetOps = mkVerdict(
      Map(
        0 -> mkView(Seq("mover", "alice")),
        1 -> mkView(Seq("asset1Ops")),
        2 -> mkView(Seq(dsoParty.toProtoPrimitive)),
      ),
      submittingParties = Seq("moverOps"),
    )

    "the amulet version is above 0.1.24" should {

      "split the extra burn among assets" in {
        val result = computeWithFeaturedAppParties(featuredAppParties, Seq((summary, verdict)))
        result should have size 1
        result.head.submittingAppProvider shouldBe Some("mover")
        result.head.appProviderParties shouldBe Seq("mover")
        result.head.appActivityWeights shouldBe Seq(1000L)
        // the extra burn of 200 is split 120 / 80 between asset1 and the dso;
        // asset1 is capped at 100, its excess of 20 goes to the dso,
        // and the dso's 100 is attributed to the mover scaled by 0.1
        result.head.assetActivityProviderParties shouldBe Seq("asset1", "mover")
        result.head.assetActivityWeights shouldBe Seq(100L, 10L)
      }

      "not attribute the confirmations of an asset's ops party to the asset" in {
        val result =
          computeWithFeaturedAppParties(featuredAppParties, Seq((summary, verdictWithAssetOps)))
        result should have size 1
        result.head.submittingAppProvider shouldBe Some("mover")
        result.head.appProviderParties shouldBe Seq("mover")
        result.head.appActivityWeights shouldBe Seq(1000L)
        // the extra burn of 200 all goes to the dso, which is capped at 100,
        // and attributed to the mover scaled by 0.1
        result.head.assetActivityProviderParties shouldBe Seq("mover")
        result.head.assetActivityWeights shouldBe Seq(10L)
      }
    }

    "the amulet version is up to 0.1.24" should {

      def assertUserSubmitted(amuletVersion: Option[String]) = {
        val result = computeWithFeaturedAppParties(
          featuredAppParties,
          Seq((summary, verdict)),
          amuletVersion = amuletVersion,
        )
        result should have size 1
        result.head.submittingAppProvider shouldBe None
        // the total burn of 1000 is split 500 / 300 between the featured app envelopes
        result.head.appProviderParties.zip(result.head.appActivityWeights) shouldBe Seq(
          "asset1" -> 375L,
          "mover" -> 625L,
        )
        result.head.assetActivityProviderParties shouldBe empty
        result.head.assetActivityWeights shouldBe empty
      }

      "compute it as user submitted" in {
        assertUserSubmitted(Some(DarResources.amulet_0_1_24.metadata.version.toString))
      }

      // This can happen only for rounds before we added ingestion of AmuletRules
      "compute it as user submitted if the AmuletRules are not ingested" in {
        assertUserSubmitted(None)
      }

      "not attribute the confirmations of an ops party to its provider" in {
        val result = computeWithFeaturedAppParties(
          featuredAppParties,
          Seq((summary, verdictWithAssetOps)),
          amuletVersion = Some(DarResources.amulet_0_1_24.metadata.version.toString),
        )
        result should have size 1
        result.head.submittingAppProvider shouldBe None
        // only the envelope confirmed by the mover has a featured app confirmer
        result.head.appProviderParties.zip(result.head.appActivityWeights) shouldBe Seq(
          "mover" -> 1000L
        )
      }
    }
  }

  // DvP settlement example from CIP-104
  // 5 nodes, each with its own view and envelope:
  //   Node 1 (Exercise SimpleDvp.Settle):                  confirmers = {Alice, Bob},   12000 bytes
  //   Node 2 (Exercise SimpleAsset.Transfer EUR to Bob):   confirmers = {Alice, Bank1},  4000 bytes
  //   Node 3 (Create SimpleAsset 1 EUR):                   confirmers = {Bank1},          2000 bytes
  //   Node 4 (Exercise SimpleAsset.Transfer USD to Alice): confirmers = {Bob, Bank2},    5000 bytes
  //   Node 5 (Create SimpleAsset 1 USD):                   confirmers = {Bank2},          2000 bytes
  //   total_confirmation_request_traffic = 25000 bytes
  "DvP settlement example" when {

    val dvpViews: Map[Int, v30.TransactionView] = Map(
      0 -> mkView(Seq("Alice", "Bob")), // Node 1: Exercise SimpleDvp.Settle
      1 -> mkView(Seq("Alice", "Bank1")), // Node 2: Exercise SimpleAsset.Transfer EUR to Bob
      2 -> mkView(Seq("Bank1")), // Node 3: Create SimpleAsset 1 EUR
      3 -> mkView(Seq("Bob", "Bank2")), // Node 4: Exercise SimpleAsset.Transfer USD to Alice
      4 -> mkView(Seq("Bank2")), // Node 5: Create SimpleAsset 1 USD
    )
    val dvpVerdict = mkVerdict(dvpViews)
    val dvpSummary = mkSummary(
      25000L,
      Seq(
        mkEnvelope(12000L, Seq(0)), // Node 1: 12000 bytes
        mkEnvelope(4000L, Seq(1)), // Node 2: 4000 bytes
        mkEnvelope(2000L, Seq(2)), // Node 3: 2000 bytes
        mkEnvelope(5000L, Seq(3)), // Node 4: 5000 bytes
        mkEnvelope(2000L, Seq(4)), // Node 5: 2000 bytes
      ),
    )

    // Batch of two DvP settlements, see the CIP-104 example 2.3:
    // Nodes 6-10 repeat Nodes 1-5, with Bank3 in place of Bank2
    val batchViews: Map[Int, v30.TransactionView] = dvpViews ++ dvpViews.map { case (id, view) =>
      (id + 5) -> mkView(view.informees.map(p => if (p == "Bank2") "Bank3" else p))
    }
    val batchSummary = mkSummary(
      50000L,
      dvpSummary.envelopeTrafficSummarys ++ dvpSummary.envelopeTrafficSummarys.map(envelope =>
        envelope.copy(viewIds = envelope.viewIds.map(_ + 5))
      ),
    )

    "user submitted" should {

      "Example 1: Bank1 is the only app provider" in {
        val result = computeWith(Set("Bank1"), Seq((dvpSummary, dvpVerdict)))
        result should have size 1
        result.head.appProviderParties shouldBe Seq("Bank1")
        result.head.appActivityWeights shouldBe Seq(25000L)
      }

      "Example 2: Bank1 and Bank2 are app providers" in {
        val result = computeWith(Set("Bank1", "Bank2"), Seq((dvpSummary, dvpVerdict)))
        result should have size 1
        result.head.appProviderParties shouldBe Seq("Bank1", "Bank2")
        result.head.appActivityWeights shouldBe Seq(11538L, 13461L)
      }

      "Example 3: Alice, Bank1, and Bank2 are app providers" in {
        val result =
          computeWith(Set("Alice", "Bank1", "Bank2"), Seq((dvpSummary, dvpVerdict)))
        result should have size 1
        result.head.appProviderParties shouldBe Seq("Alice", "Bank1", "Bank2")
        result.head.appActivityWeights shouldBe Seq(14000L, 4000L, 7000L)
      }
    }

    "submitted by the featured asset mover Bob" should {

      "Example 2.1: Bank1 is the only featured asset provider" in {
        val result = computeFASubmitted(
          Map(assetMover("Bob"), asset("Bank1")),
          dvpSummary,
          dvpVerdict,
        )
        assertAppActivity(result, "Bob" -> 25000L)
        // uncapped 5000, capped at 2500
        assertAssetActivity(result, "Bank1" -> 2500L)
      }

      "Example 2.2: Bank1 and Bank2 are featured asset providers" in {
        val result = computeFASubmitted(
          Map(assetMover("Bob"), asset("Bank1"), asset("Bank2")),
          dvpSummary,
          dvpVerdict,
        )
        assertAppActivity(result, "Bob" -> 25000L)
        // Bank2 is capped at 2500, and its excess of 192 is redistributed to Bank1
        assertAssetActivity(result, "Bank1" -> 2499L, "Bank2" -> 2500L)
      }

      "Example 2.3: a batch of two DvP settlements, with Bank3 in place of Bank2" in {
        val result = computeFASubmitted(
          Map(assetMover("Bob"), asset("Bank1"), asset("Bank2"), asset("Bank3")),
          batchSummary,
          mkVerdict(batchViews),
        )
        assertAppActivity(result, "Bob" -> 50000L)
        // Cap is 5k, no asset exceeds the cap, so no activity is redistributed
        assertAssetActivity(result, "Bank1" -> 4615L, "Bank2" -> 2692L, "Bank3" -> 2692L)
      }

      "Example 2.4: the DSO replaces Bank1" in {
        val dsoVerdict = mkVerdict(dvpViews.view.mapValues { view =>
          mkView(view.informees.map(p => if (p == "Bank1") "DSO" else p))
        }.toMap)

        // modified Example 2.1:
        val onlyDso = computeFASubmitted(Map(assetMover("Bob")), dvpSummary, dsoVerdict)
        assertAppActivity(onlyDso, "Bob" -> 25000L)
        assertAssetActivity(onlyDso, "Bob" -> 250L)

        // modified Example 2.2:
        val withBank2 = computeFASubmitted(
          Map(assetMover("Bob"), asset("Bank2")),
          dvpSummary,
          dsoVerdict,
        )
        assertAssetActivity(withBank2, "Bank2" -> 2500L, "Bob" -> 249L)

        // modified Example 2.3:
        val batch = computeFASubmitted(
          Map(assetMover("Bob"), asset("Bank2"), asset("Bank3")),
          batchSummary,
          mkVerdict(batchViews.view.mapValues { view =>
            mkView(view.informees.map(p => if (p == "Bank1") "DSO" else p))
          }.toMap),
        )
        assertAppActivity(batch, "Bob" -> 50000L)
        assertAssetActivity(batch, "Bank2" -> 2692L, "Bank3" -> 2692L, "Bob" -> 461L)
      }
    }
  }

  "findFASubmitter" should {
    val featuredAppParties = ScanRewardsReferenceStore.FeaturedAppParties(
      providers = Map(
        "providerA" -> (FeaturedAppKind.FAK_GENERAL, BigDecimal(1)),
        "providerB" -> (FeaturedAppKind.FAK_GENERAL, BigDecimal(1)),
      ),
      opsPartyProviders = Map(
        "opsA" -> "providerA",
        "opsB" -> "providerB",
        // shared ops party, resolved to the lexicographically smaller provider
        "opsAB" -> "providerA",
        // providerA lists providerB as an ops party
        "providerB" -> "providerA",
      ),
    )
    def find(submitters: Seq[String], confirmers: Set[String]) =
      AppActivityComputation.findFASubmitter(submitters, confirmers, featuredAppParties)

    "find a provider that submits and confirms" in {
      find(Seq("providerA"), Set("providerA", "other")) shouldBe Some("providerA")
    }

    "find a provider whose ops party submits" in {
      find(Seq("opsA"), Set("providerA")) shouldBe Some("providerA")
    }

    "return None if the provider does not confirm" in {
      // An ops party confirming is not considered FA submitted
      find(Seq("opsA"), Set("opsA")) shouldBe None
      find(Seq("providerA", "opsB"), Set("other")) shouldBe None
    }

    "return None if no submitter is a featured app party" in {
      find(Seq("other"), Set("providerA", "providerB", "other")) shouldBe None
      find(Seq.empty, Set("providerA")) shouldBe None
    }

    "pick the app whose submitter appears earliest" in {
      find(Seq("opsB", "providerA"), Set("providerA", "providerB")) shouldBe Some("providerB")
      find(Seq("providerA", "opsB"), Set("providerA", "providerB")) shouldBe Some("providerA")
    }

    "skip submitters whose provider does not confirm" in {
      find(Seq("other", "opsA", "opsB"), Set("providerB")) shouldBe Some("providerB")
    }

    "pick the lexicographically smallest app for a shared ops party" in {
      find(Seq("opsAB"), Set("providerA", "providerB")) shouldBe Some("providerA")
    }

    "map a provider to itself, even if it is an ops party of another provider" in {
      find(Seq("providerB"), Set("providerA", "providerB")) shouldBe Some("providerB")
      find(Seq("providerB"), Set("providerA")) shouldBe None
    }
  }

  // Some more scenarios related to FA submitted transactions
  "computeForFASubmitted" should {

    "does not record asset activity if the submitting app is not an asset mover" in {
      val verdict = mkVerdict(Map(0 -> mkView(Seq("Bob", "Bank1", "DSO"))))
      val summary = mkSummary(1000L, Seq(mkEnvelope(1000L, Seq(0))))

      for (kind <- Seq(FeaturedAppKind.FAK_GENERAL, FeaturedAppKind.FAK_ASSET)) {
        val result = computeFASubmitted(
          Map("Bob" -> (kind, BigDecimal(1)), asset("Bank1")),
          summary,
          verdict,
        )
        assertAppActivity(result, "Bob" -> 1000L)
        assertAssetActivity(result)
      }
    }

    "apply the composition weights of the submitting app and the assets" in {
      val verdict = mkVerdict(
        Map(0 -> mkView(Seq("Bank1")), 1 -> mkView(Seq("Bank2")), 2 -> mkView(Seq("DSO")))
      )
      val summary = mkSummary(
        1000L,
        Seq(mkEnvelope(300L, Seq(0)), mkEnvelope(300L, Seq(1)), mkEnvelope(400L, Seq(2))),
      )

      val result = computeFASubmitted(
        Map(
          assetMover("Bob", BigDecimal("0.5")),
          asset("Bank1", BigDecimal(3)),
          asset("Bank2", BigDecimal("0.3")),
        ),
        summary,
        verdict,
        params = defaultAssetParams.copy(dsoAppActivityWeight = BigDecimal("0.7")),
      )
      // weights are not applied to the app activity
      assertAppActivity(result, "Bob" -> 1000L)

      // But asset activity does change with weights
      // uncapped: Bank1 = 60, Bank2 = 60, DSO = 80, all below the cap of 100
      // Bank1 = floor(60 * 0.5 * 3), Bank2 = floor(60 * 0.5 * 0.3), Bob = floor(80 * 0.5 * 0.7)
      assertAssetActivity(
        result,
        "Bank1" -> 90L,
        "Bank2" -> 9L,
        "Bob" -> 28L,
      )
    }

    "redistribute the capped activity, but maintain overall cap" in {
      val verdict = mkVerdict(
        Map(0 -> mkView(Seq("Bank1")), 1 -> mkView(Seq("Bank2")), 2 -> mkView(Seq("Bank3")))
      )
      val summary = mkSummary(
        1000L,
        Seq(mkEnvelope(500L, Seq(0)), mkEnvelope(300L, Seq(1)), mkEnvelope(200L, Seq(2))),
      )

      val result = computeFASubmitted(
        Map(assetMover("Bob"), asset("Bank1"), asset("Bank2"), asset("Bank3")),
        summary,
        verdict,
        params = defaultAssetParams.copy(
          maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.05")
        ),
      )
      // uncapped: Bank1 = 100, Bank2 = 60, Bank3 = 40, the cap is 50
      // Bank3 gets all of the excess of 60, but is capped at 50 again
      assertAssetActivity(
        result,
        "Bank1" -> 50L,
        "Bank2" -> 50L,
        "Bank3" -> 50L,
      )
    }
  }

  // The example values of CIP-104
  private val defaultAssetParams = AssetActivityParams(
    extraFeaturedAssetActivityWeight = BigDecimal("0.2"),
    maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.1"),
    dsoAppActivityWeight = BigDecimal("0.1"),
  )

  private def assetMover(provider: String, weight: BigDecimal = BigDecimal(1)) =
    provider -> (FeaturedAppKind.FAK_ASSETMOVING, weight)

  private def asset(provider: String, weight: BigDecimal = BigDecimal(1)) =
    provider -> (FeaturedAppKind.FAK_ASSET, weight)

  private def assertAppActivity(
      record: DbAppActivityRecordStore.AppActivityRecordT,
      expected: (String, Long)*
  ) = {
    record.roundNumber shouldBe 7L
    record.submittingAppProvider shouldBe Some("Bob")
    record.appProviderParties.zip(record.appActivityWeights) shouldBe expected.sortBy(_._1)
  }

  private def assertAssetActivity(
      record: DbAppActivityRecordStore.AppActivityRecordT,
      expected: (String, Long)*
  ) =
    record.assetActivityProviderParties.zip(record.assetActivityWeights) shouldBe
      expected.sortBy(_._1)

  private def computeFASubmitted(
      featuredAppProviders: Map[String, (FeaturedAppKind, BigDecimal)],
      summary: DbScanVerdictStore.TrafficSummaryT,
      verdict: v30.Verdict,
      dsoParty: String = "DSO",
      params: AssetActivityParams = defaultAssetParams,
  ): DbAppActivityRecordStore.AppActivityRecordT =
    new AppActivityComputation(mock[ScanRewardsReferenceStore], loggerFactory)(
      directExecutionContext
    ).computeForFASubmitted(
      summary,
      verdict,
      7L,
      "Bob",
      featuredAppProviders,
      dsoParty,
      params,
    )

  private val roundOpensAt = CantonTimestamp.ofEpochSecond(0)

  /** Run computation and extract just the AppActivityRecordT results.
    * All featured providers use the default activity weight of 1.0.
    */
  private def computeWith(
      featured: Set[String],
      input: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)],
      svParticipantIds: Set[String] = Set("someSvParticipant"),
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT] =
    computeWithWeights(featured.map(_ -> BigDecimal(1)).toMap, input, svParticipantIds)

  /** Run computation with explicit per-provider activity weights. */
  private def computeWithWeights(
      featuredWeights: Map[String, BigDecimal],
      input: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)],
      svParticipantIds: Set[String] = Set("someSvParticipant"),
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT] =
    computeWithFeaturedAppParties(
      ScanRewardsReferenceStore.FeaturedAppParties(
        providers = featuredWeights.map { case (party, weight) =>
          party -> (FeaturedAppKind.FAK_GENERAL, weight)
        },
        opsPartyProviders = Map.empty,
      ),
      input,
      svParticipantIds,
    )

  private def computeWithFeaturedAppParties(
      featuredAppParties: ScanRewardsReferenceStore.FeaturedAppParties,
      input: Seq[(DbScanVerdictStore.TrafficSummaryT, v30.Verdict)],
      svParticipantIds: Set[String] = Set("someSvParticipant"),
      assetActivityParams: AssetActivityParams = defaultAssetParams,
      amuletVersion: Option[String] = Some(DarResources.amulet.latest.metadata.version.toString),
  ): Seq[DbAppActivityRecordStore.AppActivityRecordT] = {
    val store = mock[ScanRewardsReferenceStore]
    val amuletConfig = amuletVersion.map { version =>
      val packageConfig = SpliceUtil.readPackageConfig()
      SpliceUtil.defaultAmuletConfig(
        SpliceUtil.defaultInitialTickDuration,
        100L,
        SynchronizerId.tryFromString("domain::id"),
        initialPackageConfig = new PackageConfig(
          version,
          packageConfig.amuletNameService,
          packageConfig.dsoGovernance,
          packageConfig.validatorLifecycle,
          packageConfig.wallet,
          packageConfig.walletPayments,
        ),
      )
    }
    when(store.lookupAmuletConfigAsOf(any[CantonTimestamp])(any[TraceContext]))
      .thenReturn(Future.successful(amuletConfig))
    when(store.key).thenReturn(
      ScanRewardsReferenceStore.Key(dsoParty, SynchronizerId.tryFromString("domain::id"))
    )
    when(store.lookupActiveOpenMiningRounds(any[Seq[CantonTimestamp]])(any[TraceContext]))
      .thenAnswer { (times: Seq[CantonTimestamp]) =>
        Future.successful(
          times
            .map(
              _ -> ScanRewardsReferenceStore.ActiveRoundInfo(0L, roundOpensAt, assetActivityParams)
            )
            .toMap
        )
      }
    when(store.lookupFeaturedAppPartiesAsOf(any[CantonTimestamp])(any[TraceContext]))
      .thenReturn(Future.successful(featuredAppParties))
    when(store.lookupSvParticipantIdsAsOf(any[CantonTimestamp])(any[TraceContext]))
      .thenReturn(Future.successful(svParticipantIds))
    new AppActivityComputation(store, loggerFactory)(directExecutionContext)
      .computeActivities(input)(traceContext)
      .futureValue
      .flatMap { case (_, _, recordO, _) => recordO }
  }

  private def mkVerdict(
      views: Map[Int, v30.TransactionView],
      timestamp: CantonTimestamp = ts,
      verdictResult: v30.VerdictResult = v30.VerdictResult.VERDICT_RESULT_ACCEPTED,
      submittingParties: Seq[String] = Seq("submitter"),
  ): v30.Verdict = {
    val proto = ProtoTimestamp(seconds = timestamp.getEpochSecond)
    v30.Verdict(
      submittingParties = submittingParties,
      submittingParticipantUid = defaultSubmittingParticipant,
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
