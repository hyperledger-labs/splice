package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv1,
  allocationv1,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingapp
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.scan.automation.RewardComputationTrigger
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment

import scala.jdk.CollectionConverters.*
import scala.util.Random

// Tests the TrafficSummary ingestion and AppActivityRecord creation for each
// event as described in CIP-104
//
// DvP settlement from TokenStandardTest is used here just to confirm distribution of rewards
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
class TrafficBasedRewardsTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with ExternallySignedPartyTestUtil
    with TokenStandardTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        Seq(
          sv1ValidatorBackend,
          aliceValidatorBackend,
          bobValidatorBackend,
          splitwellValidatorBackend,
        ).foreach { backend =>
          backend.participantClient.upload_dar_unless_exists(tokenStandardTestDarPath)
        }
      })
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.withPausedTrigger[RewardComputationTrigger]
        )(config)
      )

  "App activity records are created for featured app parties" in { implicit env =>
    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val venuePartyHint = s"venue-party-${Random.nextInt()}"
    val venueParty = splitwellValidatorBackend.onboardUser(
      splitwellWalletClient.config.ledgerApiUser,
      Some(
        PartyId.tryFromProtoPrimitive(
          s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
        )
      ),
    )

    aliceWalletClient.tap(1000)
    bobWalletClient.tap(1000)

    assertOldestOpenRound(0)

    clue("Reward accounting endpoints return 404 before any data is available") {
      sv1ScanBackend.getRewardAccountingEarliestAvailableRound() shouldBe None
      sv1ScanBackend.getRewardAccountingActivityTotals(0L) shouldBe None
    }

    // Here we perform all settlements with verdict ingestion paused just to
    // confirm that activity record computations does happen properly even when
    // the ingestion is catching up, by reading the Tcs store data for the
    // archived rounds. I.e., pausing is not necessary, it merely improves test coverage.
    //
    // Sequence of actions
    //   Open rounds | Action
    //   ------------+--------------------------------------
    //   3, 4        | settle id0, grant venue FAP
    //   4, 5        | settle id1, grant alice FAP
    //   5, 6        | settle id2, cancel venue FAP
    //   6, 7        | settle id3
    //   7, 8        | settle id4
    val (updateId0, updateId1, updateId2, updateId3, updateId4) =
      pauseScanVerdictIngestionWithin(sv1ScanBackend) {

        // 3 initial advances to get open rounds with staggered opensAt
        for (round <- 1 to 3) {
          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(round.toLong)
        }

        val id0 = settleTrade(aliceParty, bobParty, venueParty)
        grantFeaturedAppRight(splitwellWalletClient)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(4)

        val id1 = settleTrade(aliceParty, bobParty, venueParty)
        grantFeaturedAppRight(aliceWalletClient)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(5)

        val id2 = settleTrade(aliceParty, bobParty, venueParty)
        actAndCheck(
          "Cancel venue's featured app right",
          retryCommandSubmission(splitwellWalletClient.cancelFeaturedAppRight()),
        )(
          "Wait for right cancellation to be ingested",
          _ => sv1ScanBackend.lookupFeaturedAppRight(venueParty) shouldBe None,
        )

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(6)

        val id3 = settleTrade(aliceParty, bobParty, venueParty)

        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(7)

        val id4 = settleTrade(aliceParty, bobParty, venueParty)

        (id0, id1, id2, id3, id4)
      }

    def fetchEvent(updateId: String, label: String): definitions.EventHistoryItem =
      clue(s"Fetch event $label") {
        eventually() {
          sv1ScanBackend
            .getEventById(updateId, Some(CompactJson))
            .getOrElse(fail(s"Expected event for updateId $updateId"))
        }
      }

    clue("updateId0") {
      val event = fetchEvent(updateId0, "updateId0")
      event.update shouldBe defined
      assertTrafficSummary(event, "updateId0")
      assertNoAppActivity(event, "updateId0")
    }

    // We don't see activity for updateId1, even though venue was granted FAP
    // before this event happened, because the oldest open round for updateId1
    // was 4 and the round 4 opened before venue was granted FAP.
    clue("updateId1") {
      val event = fetchEvent(updateId1, "updateId1")
      assertTrafficSummary(event, "updateId1")
      assertNoAppActivity(event, "updateId1")
    }

    clue("updateId2") {
      val event = fetchEvent(updateId2, "updateId2")
      assertTrafficSummary(event, "updateId2")
      assertAppActivity(event, "updateId2", Set(venueParty), expectedRound = 5)
    }

    clue("updateId3") {
      val event = fetchEvent(updateId3, "updateId3")
      assertTrafficSummary(event, "updateId3")
      assertAppActivity(event, "updateId3", Set(venueParty, aliceParty), expectedRound = 6)
    }

    clue("updateId4") {
      val event = fetchEvent(updateId4, "updateId4")
      assertTrafficSummary(event, "updateId4")
      assertAppActivity(event, "updateId4", Set(aliceParty), expectedRound = 7)
    }

    // -- Reward pipeline endpoint checks --------------------------------------
    // ScanAggregationTrigger runs unpaused throughout the test and has already
    // aggregated completed rounds. Run the paused RewardComputationTrigger to
    // compute rewards, then verify the reward accounting HTTP endpoints.

    clue("Run the reward computation trigger") {
      sv1ScanBackend.automation
        .trigger[RewardComputationTrigger]
        .runOnce()
        .futureValue
    }

    val earliest = clue("Verify earliest available round is returned") {
      val e = sv1ScanBackend.getRewardAccountingEarliestAvailableRound()
      e shouldBe defined
      e.value
    }

    clue("Verify activity totals for the computed round") {
      val totals = sv1ScanBackend.getRewardAccountingActivityTotals(earliest)
      totals.value.roundNumber shouldBe earliest
      totals.value.activityRecordsCount should be > 0L
    }

    clue("Verify root hash is available") {
      val rootHash = sv1ScanBackend.getRewardAccountingRootHash(earliest)
      rootHash shouldBe defined
      rootHash.value.roundNumber shouldBe earliest
      rootHash.value.rootHash should have length 64 // hex-encoded SHA-256
    }

    clue("Verify batch lookup for root hash returns batch contents") {
      val rootHashHex = sv1ScanBackend.getRewardAccountingRootHash(earliest).value.rootHash
      sv1ScanBackend.getRewardAccountingBatch(earliest, rootHashHex) shouldBe defined
    }

    clue("Verify 404 for non-existent data") {
      sv1ScanBackend.getRewardAccountingActivityTotals(earliest + 100) shouldBe None
      sv1ScanBackend.getRewardAccountingRootHash(earliest + 100) shouldBe None
      sv1ScanBackend.getRewardAccountingBatch(earliest, "0" * 64) shouldBe None
    }
  }

  private def assertTrafficSummary(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
  ): Unit = {
    withClue(s"$cluePrefix should have traffic summary") {
      event.trafficSummary shouldBe defined
    }
    event.trafficSummary.foreach { summary =>
      withClue(s"$cluePrefix traffic summary should have positive total cost") {
        summary.totalTrafficCost should be > 0L
      }
      withClue(s"$cluePrefix traffic summary should have envelope costs") {
        summary.envelopeTrafficSummaries should not be empty
        summary.envelopeTrafficSummaries.foreach { env =>
          env.trafficCost should be > 0L
        }
      }
    }
  }

  private def assertNoAppActivity(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
  ): Unit = {
    withClue(s"$cluePrefix should not have app activity") {
      event.appActivityRecords shouldBe None
    }
  }

  private def assertAppActivity(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
      expectedProviders: Set[PartyId],
      expectedRound: Long,
  ): Unit = {
    withClue(s"$cluePrefix should have app activity") {
      event.appActivityRecords shouldBe defined
    }
    val totalTrafficCost = event.trafficSummary.value.totalTrafficCost
    event.appActivityRecords.foreach { activity =>
      withClue(s"$cluePrefix app activity round number") {
        activity.roundNumber shouldBe expectedRound
      }
      withClue(s"$cluePrefix app activity provider parties") {
        activity.records.map(_.party).toSet shouldBe expectedProviders.map(_.toProtoPrimitive)
      }
      withClue(s"$cluePrefix each app activity weight should be positive") {
        activity.records.foreach { r =>
          r.weight should be > 0L
        }
      }
      val weightSum = activity.records.map(_.weight).sum
      val numFeaturedAppParties = expectedProviders.size.toLong
      withClue(
        s"$cluePrefix sum of weights should be within [totalTrafficCost - numFeaturedAppParties, totalTrafficCost]"
      ) {
        weightSum should be > (totalTrafficCost - numFeaturedAppParties)
        weightSum should be <= totalTrafficCost
      }
    }
  }

  private def assertOldestOpenRound(
      expectedOldestRound: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"Asserting oldest open round=$expectedOldestRound") {
      eventually() {
        val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val roundNumbers = openRounds.map(_.contract.payload.round.number.toLong).sorted
        roundNumbers should have size 3
        roundNumbers.head shouldBe expectedOldestRound
      }
    }
  }

  private def settleTrade(
      aliceParty: PartyId,
      bobParty: PartyId,
      venueParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): String = {
    val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
    val aliceTransferAmount = walletUsdToAmulet(100.0)
    val bobTransferAmount = walletUsdToAmulet(20.0)
    val CreateAllocationRequestResult(trade, aliceRequest, bobRequest) =
      createAllocationRequestViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
      )

    val aliceAllocationId = createAllocation(aliceWalletClient, aliceRequest, "leg0")
    val bobAllocationId = createAllocation(bobWalletClient, bobRequest, "leg1")

    clue("Wait for allocations to be ingested by SV1") {
      eventuallySucceeds() {
        sv1ScanBackend.getAllocationCancelContext(aliceAllocationId)
        sv1ScanBackend.getAllocationCancelContext(bobAllocationId)
      }
    }

    clue("Settlement venue settles the trade") {
      val aliceContext = sv1ScanBackend.getAllocationTransferContext(aliceAllocationId)
      val bobContext = sv1ScanBackend.getAllocationTransferContext(bobAllocationId)

      def mkExtraArg(context: ChoiceContextWithDisclosures) =
        new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)

      val settlementChoice = new tradingapp.OTCTrade_Settle(
        Map(
          "leg0" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
            aliceAllocationId,
            mkExtraArg(aliceContext),
          ),
          "leg1" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
            bobAllocationId,
            mkExtraArg(bobContext),
          ),
        ).asJava
      )

      val tx =
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(venueParty),
            commands = trade.id
              .exerciseOTCTrade_Settle(settlementChoice)
              .commands()
              .asScala
              .toSeq,
            disclosedContracts = aliceContext.disclosedContracts ++ bobContext.disclosedContracts,
          )
      tx.getUpdateId
    }
  }

  private def createAllocation(
      walletClient: WalletAppClientReference,
      request: allocationrequestv1.AllocationRequestView,
      legId: String,
  ): allocationv1.Allocation.ContractId = {
    val transferLeg = request.transferLegs.get(legId)
    val senderParty = PartyId.tryFromProtoPrimitive(transferLeg.sender)
    import com.digitalasset.canton.util.ShowUtil.*
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    val (_, allocation) = actAndCheck(
      show"Create allocation for leg $legId with sender $senderParty", {
        walletClient.allocateAmulet(
          new allocationv1.AllocationSpecification(
            request.settlement,
            legId,
            transferLeg,
          )
        )
      },
    )(
      show"There exists an allocation from $senderParty",
      _ => {
        val allocations = walletClient.listAmuletAllocations()
        allocations should have size 1 withClue "AmuletAllocations"
        allocations.head
      },
    )
    new allocationv1.Allocation.ContractId(allocation.contractId.contractId)
  }
}
