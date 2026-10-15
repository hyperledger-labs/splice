package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.event.Event
import com.daml.ledger.api.v2.transaction_filter
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.TransactionWrapper
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import java.time.Duration
import java.util.UUID
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv1,
  allocationv1,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight_Update
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_UpdateFeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_UpdateFeaturedAppRight
import org.lfdecentralizedtrust.splice.console.{ScanAppBackendReference, WalletAppClientReference}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingapp
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson
import definitions.GetRewardAccountingBatchResponse
import definitions.GetRewardAccountingActivityTotalsResponse
import definitions.GetRewardAccountingRootHashResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.{
  CalculateRewardsTrigger,
  CalculateRewardsDryRunTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredAmuletTransferInstructionTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigUtil,
  ChoiceContextWithDisclosures,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random

// Tests the TrafficSummary ingestion and AppActivityRecord creation for each
// event as described in CIP-104
//
// DvP settlement from TokenStandardTest is used here just to confirm distribution of rewards
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
abstract class TrafficBasedRewardsTimeBasedIntegrationTestBase
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with AmuletConfigUtil
    with ExternallySignedPartyTestUtil
    with TokenStandardTest {

  protected def rewardConfigMode: TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode

  // Applicable for rounds >= 7
  private val aliceActivityWeight = BigDecimal("2.2")

  private def dryRunEnabled: Boolean =
    rewardConfigMode == TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.DryRun

  private def mintingTrafficBased: Boolean =
    rewardConfigMode == TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased

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
      .addConfigTransform((_, config) =>
        ConfigTransforms.withRewardConfig(
          InitialRewardConfig(
            mintingVersion =
              if (mintingTrafficBased)
                TrafficBasedRewardsTimeBasedIntegrationTestBase.trafficBasedAppRewards
              else TrafficBasedRewardsTimeBasedIntegrationTestBase.featuredAppMarkers,
            dryRunVersion = Option.when(dryRunEnabled)(
              TrafficBasedRewardsTimeBasedIntegrationTestBase.trafficBasedAppRewards
            ),
            appRewardCouponThreshold =
              TrafficBasedRewardsTimeBasedIntegrationTestBase.appRewardCouponThreshold,
          )
        )(config)
      )
      // Pause background wallet/validator automation so that we can test round with no activity,
      // and even do calcs comparison for known transactions in a round
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
            .withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )
      // Pause SV reward collection so that it does not race against
      // advanceTimeAndWaitForRoundOpening in the activity block,
      // which would cause "Skipped N SV rewards" warnings
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
        )(config)
      )

  "CIP-104 reward accounting pipeline works" in { implicit env =>
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

    aliceWalletClient.tap(20000)
    bobWalletClient.tap(20000)

    assertOldestOpenRound(0)

    clue(
      "Reward accounting: earliest available round is 0 after bootstrap but totals are not yet computed"
    ) {
      eventually() {
        sv1ScanBackend.getRewardAccountingEarliestAvailableRound() shouldBe Some(0L)
      }
      // Activity totals are still Undetermined at this point because no
      // CalculateRewardsV2 contract exists yet (no round has closed).
      sv1ScanBackend.getRewardAccountingActivityTotals(0L) shouldBe an[
        GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsUndetermined
      ]
    }

    // Settlements are performed with verdict ingestion paused to test
    // catch-up ingestion. The initial 3 bootstrap round advances are done
    // below with verdict ingestion active.
    // The pause of CalculateRewardsTrigger is necessary to confirm contracts
    // were created for each round.
    val calculateRewardsTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsTrigger])
    val calculateRewardsDryRunTriggers =
      activeSvs.map(_.dsoAutomation.trigger[CalculateRewardsDryRunTrigger])

    // CalculateRewardsTrigger is paused for the entire test body so
    // that we can confirm CalculateRewardsV2 contracts were created
    // for each round before the triggers consume them.
    val (
      updateId0,
      updateId1,
      updateId3,
      updateId4,
      updateId5,
      updateId6,
      updateId7,
      aliceCreateId,
      svExpireId,
    ) =
      setTriggersWithin(triggersToPauseAtStart =
        calculateRewardsTriggers ++ calculateRewardsDryRunTriggers
      ) {
        // 3 initial advances with verdict ingestion active, so that the
        // meta row is created and bootstrap rounds have activity data
        // available.
        for (round <- 1 to 3) {
          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(round.toLong)
        }

        clue("Bootstrap rounds have zero activity on firstSV (no featured apps yet)") {
          assertZeroTotals(sv1ScanBackend, 0L to 2L)
        }

        // Sequence of actions
        //   Open rounds | Action
        //   ------------+--------------------------------------
        //   3, 4        | settle id0, grant venue FAP
        //   4, 5        | settle id1, grant alice FAP
        //   5, 6        | settle id2, cancel venue FAP
        //   6, 7        | settle id3, (total 2 DvP trades)
        //   7, 8        | settle id4, (total 3 DvP trades)
        //   8, 9        | no-activity
        //   9, 10       | settle id5, 1 DvP + 3 direct trades
        //   10, 11      | settle id6, (total 5 DvP trades)
        //   11, 12      | settle id7, (round not closed)
        pauseScanVerdictIngestionWithin(sv1ScanBackend) {

          val id0 = settleTrade(aliceParty, bobParty, venueParty)
          grantFeaturedAppRight(splitwellWalletClient)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(4)

          val id1 = settleTrade(aliceParty, bobParty, venueParty)
          grantFeaturedAppRight(aliceWalletClient)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(5)

          updateFeaturedAppRightWeight(aliceParty, aliceActivityWeight)

          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(6)

          val id3 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(7)

          val id4 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          // alice creates an AmuletTransferInstruction which is archived by an SV
          val (aliceCreateId, svExpireId) =
            aliceCreateAndSvExpireInstruction(aliceParty, bobParty)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(8)

          // No activity for round 8

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(9)

          // Do only one DvP; this would not generate enough activity to reward the parties.
          val id5 = settleTrade(aliceParty, bobParty, venueParty)

          // But do additional txs by alice such that only alice receives the rewards
          (1 to 3).foreach { _ =>
            val offerCid = aliceWalletClient.createTransferOffer(
              bobParty,
              BigDecimal(10.0),
              "round-9-alice-only",
              CantonTimestamp.now().plus(Duration.ofMinutes(1)),
              s"round9-transfer-${scala.util.Random.nextInt()}",
            )
            bobWalletClient.acceptTransferOffer(offerCid)
          }

          actAndCheck(
            "Cancel venue's featured app right",
            retryCommandSubmission(splitwellWalletClient.cancelFeaturedAppRight()),
          )(
            "Wait for right cancellation to be ingested",
            _ => sv1ScanBackend.lookupFeaturedAppRight(venueParty) shouldBe None,
          )

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(10)

          // Do five in a round to check nested BatchOfBatches processing
          val id6 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          advanceTimeAndWaitForRoundOpening
          assertOldestOpenRound(11)

          val id7 = settleTrade(aliceParty, bobParty, venueParty)
          settleTrade(aliceParty, bobParty, venueParty)

          clue(
            "CalculateRewardsV2 contracts should exist for each round"
          ) {
            eventually() {
              val calculateRewardsRounds =
                sv1Backend.appState.dsoStore
                  .listCalculateRewardsV2()
                  .futureValue
                  .map(_.payload.round.number)
                  .toSet
              (0L to 10L).foreach { round =>
                calculateRewardsRounds should contain(round) withClue
                  s"CalculateRewardsV2 should exist for round $round"
              }
            }
            // Test the archiveDryRunRewardAccountingContracts admin API
            // by archiving a few early rounds. The remaining rounds are
            // consumed by triggers naturally.
            if (dryRunEnabled) {
              clue("Archive dry-run CalculateRewardsV2 for rounds 0..2 via sv admin API") {
                sv1Backend.archiveDryRunRewardAccountingContracts((0L to 2L).toSeq)
              }
              clue("CalculateRewardsV2 contracts for rounds 0..2 are gone") {
                eventually() {
                  val remaining = sv1Backend.appState.dsoStore
                    .listCalculateRewardsV2()
                    .futureValue
                    .map(_.payload.round.number)
                    .toSet
                  (0L to 2L).foreach { round =>
                    remaining should not contain round withClue
                      s"CalculateRewardsV2 for round $round should be archived"
                  }
                }
              }
            }
          }

          (id0, id1, id3, id4, id5, id6, id7, aliceCreateId, svExpireId)
        }
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

    clue("updateId3") {
      val event = fetchEvent(updateId3, "updateId3")
      assertTrafficSummary(event, "updateId3")
      // Round 6 opened before the weight change, so alice still has the default weight.
      assertAppActivity(event, "updateId3", Set(venueParty, aliceParty), expectedRound = 6)
    }

    clue("updateId4") {
      val event = fetchEvent(updateId4, "updateId4")
      assertTrafficSummary(event, "updateId4")
      // From round 7 onwards we see aliceActivityWeight
      assertAppActivity(
        event,
        "updateId4",
        Set(aliceParty, venueParty),
        expectedRound = 7,
        scaledProvider = Some(aliceParty -> aliceActivityWeight),
      )
    }

    clue("Alice-submitted create TransferInstruction has app activity for alice") {
      val event = fetchEvent(aliceCreateId, "aliceCreateId")
      event.verdict shouldBe defined
      assertTrafficSummary(event, "aliceCreateId")
      assertAppActivity(
        event,
        "aliceCreateId",
        Set(aliceParty),
        expectedRound = 7,
        scaledProvider = Some(aliceParty -> aliceActivityWeight),
      )
    }

    clue("SV-submitted expire TransferInstruction creates no app activity for alice") {
      val event = fetchEvent(svExpireId, "svExpireId")
      event.verdict shouldBe defined
      assertTrafficSummary(event, "svExpireId")
      assertNoAppActivity(event, "svExpireId")
    }

    clue("updateId5") {
      val event = fetchEvent(updateId5, "updateId5")
      assertTrafficSummary(event, "updateId5")
      // Round 9: one DvP — venue has activity but will be below the coupon threshold;
      // alice's additional transfers push her above it.
      assertAppActivity(
        event,
        "updateId5",
        Set(aliceParty, venueParty),
        expectedRound = 9,
        scaledProvider = Some(aliceParty -> aliceActivityWeight),
      )
    }

    clue("updateId6") {
      val event = fetchEvent(updateId6, "updateId6")
      assertTrafficSummary(event, "updateId6")
      assertAppActivity(
        event,
        "updateId6",
        Set(aliceParty, venueParty),
        expectedRound = 10,
        scaledProvider = Some(aliceParty -> aliceActivityWeight),
      )
    }

    clue("updateId7") {
      val event = fetchEvent(updateId7, "updateId7")
      assertTrafficSummary(event, "updateId7")
      // Round 11: venue's FAP was cancelled in round 9, so only alice is a
      // featured-app provider here.
      assertAppActivity(
        event,
        "updateId7",
        Set(aliceParty),
        expectedRound = 11,
        scaledProvider = Some(aliceParty -> aliceActivityWeight),
      )
    }

    assertRewardCalcs(aliceParty, venueParty)

    // Other misc API tests
    clue("404 for non-existent batch data") {
      sv1ScanBackend.getRewardAccountingBatch(6L, "0" * 64) shouldBe None
    }
  }

  private def assertRewardCalcs(
      aliceParty: PartyId,
      venueParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue("Scan computes activity totals through round 10") {
      eventually() {
        sv1ScanBackend.getRewardAccountingActivityTotals(10L) shouldBe an[
          GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsOk
        ]
      }
    }

    val totalsByRound: Map[Long, definitions.RewardAccountingActivityTotalsOk] =
      clue("Rounds 0..10 activity totals and root hash are computed") {
        (0L to 10L).map { round =>
          val totalsOk = inside(sv1ScanBackend.getRewardAccountingActivityTotals(round)) {
            case GetRewardAccountingActivityTotalsResponse.members
                  .RewardAccountingActivityTotalsOk(t) =>
              t
          } withClue s"Round $round should have totals"
          val rootHashOk = inside(sv1ScanBackend.getRewardAccountingRootHash(round)) {
            case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
              h
          } withClue s"Round $round should have a root hash"
          rootHashOk.rootHash should have length 64 // hex-encoded SHA-256
          round -> totalsOk
        }.toMap
      }

    // Rounds 0..4 have no activity records (totals are zero);
    // rounds 5..10 have activity. Round 11 has not closed yet.
    clue(
      "Minting allowances: rounds 6, 7, 10 reward both parties; round 9 only alice"
    ) {
      def providersFor(round: Long): Set[String] =
        getMintingAllowancesForRound(round).map(_.provider).toSet

      // Log per-round, per-party minting so test failures surface concrete
      // values without needing to re-run with debug.
      (6L to 10L).foreach { round =>
        val amounts = getMintingAllowancesForRound(round)
          .map(a => s"${a.provider.split("::").head}=${a.amount}")
          .mkString(", ")
        logger.info(s"Round $round minting: $amounts")(TraceContext.empty)
      }

      // Rounds 6, 7, 10: both alice and venue did DvP trades.
      Seq(6L, 7L, 10L).foreach { round =>
        providersFor(round) shouldBe Seq(aliceParty, venueParty)
          .map(_.toProtoPrimitive)
          .toSet withClue
          s"Both parties should be rewarded in round $round"
      }

      // Round 8: no trades and background triggers are paused, so there is no
      // activity records at all.
      providersFor(8L) shouldBe Set() withClue
        "Round 8 has no activity so no minting allowances are produced"
      totalsByRound(8L).activityRecordsCount shouldBe 0L withClue
        "Round 8 has no activity records"
      totalsByRound(8L).totalAppActivityWeight shouldBe 0L withClue
        "Round 8 has no activity weight"

      // Round 9: one DvP + alice→bob transfers; venue is below the coupon threshold
      // so only alice receives minting allowances.
      providersFor(9L) shouldBe Set(aliceParty.toProtoPrimitive) withClue
        "Round 9: venue is below reward threshold so only alice should be rewarded"

    }

    // The remaining assertions cover the handling of V2 contracts created on ledger.
    def listProcessRewardsV2Rounds(): Seq[Long] =
      sv1Backend.appState.dsoStore
        .listProcessRewardsV2()
        .futureValue
        .map(_.payload.round.number)

    clue("CalculateRewards and ProcessRewards triggers consume contracts for rounds < 11") {
      eventually(40.seconds) {
        val remainingCalculate = sv1Backend.appState.dsoStore
          .listCalculateRewardsV2()
          .futureValue
          .filter(c => c.payload.round.number < 11L)
        remainingCalculate shouldBe empty withClue
          "CalculateRewardsV2 contracts for rounds < 11 should be consumed"
        val remainingProcess = listProcessRewardsV2Rounds()
          .filter(r => r < 11L)
        remainingProcess shouldBe empty withClue
          "ProcessRewardsV2 contracts for rounds < 11 should be consumed"
      }
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

  /** Alice creates an AmuletTransferInstruction with a short deadline, then we
    * advance past the deadline and have the SV trigger archive it. Returns
    * (aliceCreateId, svExpireId).
    */
  private def aliceCreateAndSvExpireInstruction(
      aliceParty: PartyId,
      bobParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): (String, String) = {
    val participant = aliceValidatorBackend.participantClientWithAdminToken
    val beginOffsetExclusive = participant.ledger_api.state.end()

    val trackingId = s"alice-instr-${UUID.randomUUID()}"
    val expiry = Duration.ofMinutes(5)
    val createResult = aliceWalletClient.createTokenStandardTransfer(
      receiver = bobParty,
      amount = BigDecimal(1.0),
      description = "alice-to-bob transfer instruction",
      expiresAt = getLedgerTime.plus(expiry),
      trackingId = trackingId,
    )
    val instructionCid = inside(createResult.output) {
      case definitions.TransferInstructionResultOutput.members
            .TransferInstructionPending(value) =>
        value.transferInstructionCid
    }

    advanceTime(expiry.plusSeconds(60))

    aliceWalletClient.tap(1)

    // ExpiredAmuletTransferInstructionTrigger is paused by default in test config
    // so we explicitly resume it for the contract to be archived.
    setTriggersWithin(triggersToResumeAtStart =
      Seq(sv1Backend.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTransferInstructionTrigger])
    ) {
      eventually() {
        aliceWalletClient.listTokenStandardTransfers() shouldBe empty
      }
    }

    findCreateAndArchiveUpdateIds(instructionCid, beginOffsetExclusive, aliceParty)
  }

  /** Query alice's participant ledger for the create and expire `contractId`
    * between the `beginOffsetExclusive` and the current ledger end.
    */
  private def findCreateAndArchiveUpdateIds(
      contractId: String,
      beginOffsetExclusive: Long,
      party: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): (String, String) = {
    val participant = aliceValidatorBackend.participantClientWithAdminToken
    val ledgerEnd = participant.ledger_api.state.end()

    val txFormat = transaction_filter.TransactionFormat(
      eventFormat = Some(
        transaction_filter.EventFormat(
          filtersByParty = Map(party.toLf -> transaction_filter.Filters(Nil)),
          filtersForAnyParty = None,
          verbose = false,
        )
      ),
      transactionShape = transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
    )

    val updates = participant.ledger_api.updates.updates(
      updateFormat = transaction_filter.UpdateFormat(
        includeTransactions = Some(txFormat),
        includeReassignments = None,
        includeTopologyEvents = None,
      ),
      completeAfter = PositiveInt.MaxValue,
      beginOffsetExclusive = beginOffsetExclusive,
      endOffsetInclusive = Some(ledgerEnd),
    )

    val (createUid, archiveUid) =
      updates.foldLeft((Option.empty[String], Option.empty[String])) {
        case ((cu, au), TransactionWrapper(tx)) =>
          val hasCreate = tx.events.exists(_.event match {
            case Event.Event.Created(c) => c.contractId == contractId
            case _ => false
          })
          val hasArchive = tx.events.exists(_.event match {
            case Event.Event.Exercised(e) => e.contractId == contractId && e.consuming
            case _ => false
          })
          (
            if (cu.isEmpty && hasCreate) Some(tx.updateId) else cu,
            if (au.isEmpty && hasArchive) Some(tx.updateId) else au,
          )
        case (acc, _) => acc
      }

    withClue(s"create updateId for contract $contractId")(createUid shouldBe defined)
    withClue(s"archive updateId for contract $contractId")(archiveUid shouldBe defined)
    (createUid.value, archiveUid.value)
  }

  private def assertAppActivity(
      event: definitions.EventHistoryItem,
      cluePrefix: String,
      expectedProviders: Set[PartyId],
      expectedRound: Long,
      scaledProvider: Option[(PartyId, BigDecimal)] = None,
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
      val numFeaturedAppParties = expectedProviders.size.toLong

      // Convert the assigned activity back to burn amount using app weight
      val reconstructedBurnSum = activity.records.map { r =>
        val activityWeight = scaledProvider match {
          case Some((party, w)) if r.party == party.toProtoPrimitive => w
          case _ => BigDecimal(1)
        }
        BigDecimal(r.weight) / activityWeight
      }.sum
      withClue(
        s"$cluePrefix reconstructed burn should be within [totalTrafficCost - numFeaturedAppParties, totalTrafficCost]"
      ) {
        reconstructedBurnSum should be >= BigDecimal(totalTrafficCost - numFeaturedAppParties)
        reconstructedBurnSum should be <= BigDecimal(totalTrafficCost)
      }
    }
  }

  private def updateFeaturedAppRightWeight(
      party: PartyId,
      newWeight: BigDecimal,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val rightCid = clue(s"Look up featured app right for $party") {
      sv1ScanBackend.lookupFeaturedAppRight(party).value.contractId
    }
    val action = new ARC_DsoRules(
      new SRARC_UpdateFeaturedAppRight(
        new DsoRules_UpdateFeaturedAppRight(
          rightCid,
          new FeaturedAppRight_Update("updating activity weight", newWeight.bigDecimal),
        )
      )
    )
    votingFlow(action, effectivity = None, accept = true, expiration = Duration.ofSeconds(60))
    clue(s"Wait for $party featured app right weight to be $newWeight") {
      eventually() {
        val weight = sv1ScanBackend
          .lookupFeaturedAppRight(party)
          .value
          .payload
          .activityWeight
          .toScala
          .map(BigDecimal(_))
        withClue(s"activity weight for $party: ") {
          weight.getOrElse(BigDecimal(1)).compare(newWeight) shouldBe 0
        }
      }
    }
  }

  private def assertZeroTotals(
      scan: ScanAppBackendReference,
      rounds: Seq[Long],
      timeout: FiniteDuration = 20.seconds,
  ): Unit =
    rounds.foreach { round =>
      eventually(timeout) {
        inside(scan.getRewardAccountingActivityTotals(round)) {
          case GetRewardAccountingActivityTotalsResponse.members
                .RewardAccountingActivityTotalsOk(t) =>
            t.activityRecordsCount shouldBe 0L withClue
              s"Round $round should have no activity records on ${scan.name}"
            t.totalAppActivityWeight shouldBe 0L withClue
              s"Round $round should have no activity weight on ${scan.name}"
            BigDecimal(t.totalAppRewardMintingAllowance) shouldBe BigDecimal(0) withClue
              s"Round $round should have no minting allowance on ${scan.name}"
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

  private def getMintingAllowancesForRound(
      round: Long
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[definitions.RewardAccountingMintingAllowance] = {
    val hash = inside(sv1ScanBackend.getRewardAccountingRootHash(round)) {
      case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
        h.rootHash
    }
    def walk(h: String): Seq[definitions.RewardAccountingMintingAllowance] =
      sv1ScanBackend.getRewardAccountingBatch(round, h).toList.flatMap {
        case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches(b) =>
          b.childHashes.flatMap(walk)
        case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances(b) =>
          b.mintingAllowances.toSeq
      }
    walk(hash)
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
    waitForAllocationsOnParticipant(
      splitwellValidatorBackend.participantClientWithAdminToken,
      venueParty,
      aliceAllocationId,
      bobAllocationId,
    )

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
        inside(walletClient.listAmuletAllocations()) {
          case (allocationRequest: HttpWalletAppClient.TokenStandard.V1AmuletAllocation) +: Nil =>
            allocationRequest
        }
      },
    )
    new allocationv1.Allocation.ContractId(allocation.contract.contractId.contractId)
  }
}

object TrafficBasedRewardsTimeBasedIntegrationTestBase {

  sealed trait RewardConfigMode
  object RewardConfigMode {
    // dryRunVersion = TrafficBased
    case object DryRun extends RewardConfigMode
    // mintingVersion = TrafficBased, dryRunVersion = None
    case object MintingTrafficBased extends RewardConfigMode
  }

  val trafficBasedAppRewards = "RewardVersion_TrafficBasedAppRewards"
  val featuredAppMarkers = "RewardVersion_FeaturedAppMarkers"

  // This threshold has been chosen to keep the venue's app activity below
  // threshold for the round 9. See the test for details.
  //
  // appRewardCouponThreshold is in USD and compared against the minting
  // allowance. For this test amuletPrice = 0.005, trafficPrice = 16.67 USD/MB,
  // and issuancePerCoupon is observed to be ~100, so 30 KB of activity ≈ 50 USD
  // (30/1000 MB × 16.67 × 100).
  val appRewardCouponThreshold = BigDecimal("50")
}

class TrafficBasedRewardsTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.MintingTrafficBased
}

class TrafficBasedRewardsDryRunTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
  override protected val rewardConfigMode
      : TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode =
    TrafficBasedRewardsTimeBasedIntegrationTestBase.RewardConfigMode.DryRun
}
