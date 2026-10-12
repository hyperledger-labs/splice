package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.{NonNegativeDuration, NonNegativeFiniteDuration}
import com.digitalasset.canton.console.LocalInstanceReference
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.metrics.MetricValue
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId

import java.time.Duration
import java.util.Optional
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.cryptohash.Hash
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  RewardConfig,
  RewardVersion,
  USD,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics.MetricsPrefix
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.GetRewardAccountingBatchResponse
import definitions.GetRewardAccountingRootHashResponse
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.RewardMetricsTrigger
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.{
  CalculateRewardsDryRunTrigger,
  CalculateRewardsTrigger,
  ConfirmationMismatchReportTrigger,
  SummarizingMiningRoundTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  ProcessRewardsDryRunTrigger,
  ProcessRewardsTrigger,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.scan.automation.{
  PruneRewardAccountingTrigger,
  RewardComputationTrigger,
}
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  ScanTestUtil,
  SpliceUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.slf4j.event.Level

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

// This test focuses on the SV app side triggers testing
// - Turning on/off of dry-run and minting-version in rewardConfig
//   And confirming that rewards processing works.
//
// - BFT read in all three SV app's reward processing triggers
//
// - Reporting of mismatches in 'Confirmation' of root-hash
//
// - Pruning of reward processing data
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class TrafficBasedRewardsSvAppTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with TimeTestUtil
    with AmuletConfigUtil
    with ScanTestUtil {

  // We deliberately modify sv2's scan activity records that makes sv2's event
  // history legitimately differ from the other scans', so the cross-scan
  // event-history consistency check cannot hold here.
  override protected def runEventHistorySanityCheck: Boolean = false

  // Long enough that advancing a few rounds does not reach it
  private val rewardAccountingRetentionPeriod: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration(SpliceUtil.defaultInitialTickDuration.asJava.multipliedBy(10))

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        ConfigTransforms.withRewardConfig(
          InitialRewardConfig(
            mintingVersion = "RewardVersion_FeaturedAppMarkers",
            dryRunVersion = None,
            appRewardCouponThreshold = BigDecimal("0"),
          )
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs_(
          _.copy(rewardAccountingRetentionPeriod = rewardAccountingRetentionPeriod)
        )(config)
      )
      // Prevent wallets from minting RewardCouponV2 before the test
      // can observe them on the DSO store.
      .withoutAutomaticRewardsCollectionAndAmuletMerging

  "Enable, disable of dryRunVersion/mintingVersion take effect at round closure" in {
    implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      aliceWalletClient.tap(20000)

      grantFeaturedAppRight(aliceWalletClient)
      grantFeaturedAppRight(bobWalletClient)

      for (round <- 1 to 3) {
        advanceRoundsToNextRoundOpening
        assertOldestOpenRound(round.toLong)
      }

      // oldest=3: rounds 3,4,5 open.
      // Next round to open is R6, it will have dryRun enabled
      clue("vote to enable dryRunVersion") {
        changeRewardConfig(enableDryRun = true)
      }

      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(4)
      doTransfer(bobParty)

      // oldest=4: rounds 4,5,6 open.
      // R7 will have the disabled config.
      clue("vote to disable dryRunVersion") {
        changeRewardConfig(enableDryRun = false)
      }

      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(5)

      // oldest=5: rounds 5,6,7 open. R8 will have
      // both dryRunVersion and mintingVersion set.
      clue("vote to enable dryRunVersion + mintingVersion") {
        changeRewardConfig(
          enableDryRun = true,
          rewardVersion = RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS,
        )
      }

      val svBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
      val calculateRewardsDryRunTriggers =
        svBackends.map(_.dsoAutomation.trigger[CalculateRewardsDryRunTrigger])
      val calculateRewardsTriggers =
        svBackends.map(_.dsoAutomation.trigger[CalculateRewardsTrigger])
      // Paused so we can drive it deterministically via runOnce; it owns the
      // calculate_rewards_v2/process_rewards_v2 active_contracts gauges.
      val sv1RewardMetricsTrigger = sv1Backend.dsoAutomation.trigger[RewardMetricsTrigger]
      val processRewardsTriggers =
        svBackends.flatMap { sv =>
          Seq(
            sv.dsoDelegateBasedAutomation.trigger[ProcessRewardsTrigger],
            sv.dsoDelegateBasedAutomation.trigger[ProcessRewardsDryRunTrigger],
          )
        }

      setTriggersWithin(
        triggersToPauseAtStart = processRewardsTriggers :+ sv1RewardMetricsTrigger
      ) {
        // Create activity for 6, 7, and 8 and confirm creation of CalculateRewardsV2
        setTriggersWithin(
          triggersToPauseAtStart = calculateRewardsDryRunTriggers ++ calculateRewardsTriggers
        ) {
          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(6)
          doTransfer(bobParty)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(7)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(8)
          doTransfer(bobParty)

          advanceRoundsToNextRoundOpening
          assertOldestOpenRound(9)

          clue("CalculateRewardsV2 are created for rounds, 6 and 8") {
            eventually() {
              val v2s = sv1Backend.appState.dsoStore.listCalculateRewardsV2().futureValue
              v2s.map(_.payload.round.number) should contain(6L)
              v2s.map(_.payload.round.number) should not contain 7L
              v2s
                .filter(_.payload.round.number == 8L)
                .map(_.payload.dryRun)
                .toSet shouldBe Set(true, false)
            }
          }

          clue("SV trigger tasks are created and metrics updated") {
            eventually() {
              // Dry run for R6 and R8
              val dryRunTasks = sv1Backend.dsoAutomation
                .trigger[CalculateRewardsDryRunTrigger]
                .retrieveTasks()
                .futureValue
                .map(_.calculateRewards.payload.round.number)
              dryRunTasks should contain allElementsOf Seq(6, 8)

              // Non-dry run for R8 only
              val mintingTasks = sv1Backend.dsoAutomation
                .trigger[CalculateRewardsTrigger]
                .retrieveTasks()
                .futureValue
                .map(_.calculateRewards.payload.round.number)
              mintingTasks should contain(8)
              mintingTasks should not contain (6)

              sv1RewardMetricsTrigger.runOnce().futureValue

              val dryRunMetric =
                metricValue(
                  sv1Backend,
                  "calculate_rewards_v2.active_contracts",
                  Map("dryRun" -> "true"),
                )
              dryRunMetric shouldBe 2L

              val mintingMetric =
                metricValue(
                  sv1Backend,
                  "calculate_rewards_v2.active_contracts",
                  Map("dryRun" -> "false"),
                )
              mintingMetric shouldBe 1L
            }
          }

          clue("CalculateRewardsV2 contracts are also visible in scan rewards reference store") {
            eventually() {
              val v2s = sv1ScanBackend.appState.rewardsReferenceStore
                .listActiveCalculateRewardsV2()
                .futureValue
              v2s.map(c =>
                (c.payload.round.number, c.payload.dryRun)
              ) should contain allElementsOf Seq((6L, true), (8L, true), (8L, false))
            }
          }

          clue("Scan metrics are updated") {
            // retrieveTasks updates the metric
            sv1ScanBackend.automation
              .trigger[RewardComputationTrigger]
              .retrieveTasks()
              .futureValue
            val dryRunMetric = metricValue(
              sv1ScanBackend,
              "scan.reward_computation.calculate_rewards_v2.active_contracts",
              Map("dryRun" -> "true"),
            )
            dryRunMetric shouldBe 2L
            val mintingMetric = metricValue(
              sv1ScanBackend,
              "scan.reward_computation.calculate_rewards_v2.active_contracts",
              Map("dryRun" -> "false"),
            )
            mintingMetric shouldBe 1L
          }
        } // Resume CalculateRewardsTrigger(s)

        clue("ProcessRewardsV2 are created and RewardMetricsTrigger reports active counts") {
          eventually() {
            sv1RewardMetricsTrigger.runOnce().futureValue
            metricValue(
              sv1Backend,
              "process_rewards_v2.active_contracts",
              Map("dryRun" -> "true"),
            ) shouldBe 2L
            metricValue(
              sv1Backend,
              "process_rewards_v2.active_contracts",
              Map("dryRun" -> "false"),
            ) shouldBe 1L
          }
        }
      } // Resume ProcessRewardsTrigger(s)

      clue("Alice and Bob have minting allowances for R6") {
        eventually() {
          val hash = inside(sv1ScanBackend.getRewardAccountingRootHash(6L)) {
            case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
              h.rootHash
          }
          val providers = walkBatch(6L, hash).map(_.provider)
          providers should contain(aliceParty.toProtoPrimitive)
          providers should contain(bobParty.toProtoPrimitive)
        }
      }

      clue("Alice and Bob have minting allowances for R8") {
        eventually() {
          val hash = inside(sv1ScanBackend.getRewardAccountingRootHash(8L)) {
            case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
              h.rootHash
          }
          val providers = walkBatch(8L, hash).map(_.provider)
          providers should contain(aliceParty.toProtoPrimitive)
          providers should contain(bobParty.toProtoPrimitive)
        }
      }

      clue("All CalculateRewardsV2 and ProcessRewardsV2 contracts consumed") {
        eventually() {
          sv1Backend.appState.dsoStore.listCalculateRewardsV2().futureValue shouldBe empty
          sv1Backend.appState.dsoStore.listProcessRewardsV2().futureValue shouldBe empty
        }
      }

      clue("Alice and Bob received RewardCouponV2 for R8") {
        eventually() {
          val coupons = sv1Backend.appState.dsoStore.listRewardCouponsV2().futureValue
          coupons.filter(c =>
            c.payload.round.number == 8L && c.payload.provider == aliceParty.toProtoPrimitive
          ) should not be empty
          coupons.filter(c =>
            c.payload.round.number == 8L && c.payload.provider == bobParty.toProtoPrimitive
          ) should not be empty
        }
      }

      clue("RewardMetricsTrigger reports active RewardCouponV2 counts") {
        setTriggersWithin(
          triggersToPauseAtStart = Seq(sv1RewardMetricsTrigger)
        ) {
          eventually() {
            sv1RewardMetricsTrigger.runOnce().futureValue
            metricValue(
              sv1Backend,
              "reward_coupon_v2.active_contracts",
              Map("bucket" -> "0"),
            ) should be > 0L
            metricValue(
              sv1Backend,
              "reward_coupon_v2.active_contracts",
              Map("bucket" -> "1"),
            ) shouldBe 0L
            metricValue(
              sv1Backend,
              "reward_coupon_v2.active_contracts",
              Map("bucket" -> "2"),
            ) shouldBe 0L
            metricValue(
              sv1Backend,
              "reward_coupon_v2.active_contracts",
              Map("bucket" -> "3"),
            ) shouldBe 0L
          }
        }
      }

      confirmBftRead(bobParty)

      confirmMismatchingRootHashIsFlagged(bobParty)

      advanceRoundsAndConfirmAllPriorRoundsPruned()
  }

  // sv2's CalculateRewardsTrigger and SummarizingMiningRoundTrigger report the
  // scan URIs that formed the BFT consensus at INFO. This method captures the
  // logs emitted while running the 'body' argument and asserts that sv2
  // obtained both the root-hash and the reward accounting totals for 'round'
  // via BFT read from sv1 and sv4.
  private def withExpectedRewardTriggersLogging[A](round: Long)(
      body: => A
  ): A = {
    val bftReadLogs =
      (SuppressionRule.forLogger[CalculateRewardsTrigger] ||
        SuppressionRule.forLogger[SummarizingMiningRoundTrigger]) &&
        SuppressionRule.LevelAndAbove(Level.INFO)

    loggerFactory.assertEventuallyLogsSeq(bftReadLogs)(
      body,
      logs => {
        // sv3 is stopped and sv2's own scan is not part of its peer BFT connection,
        // so only sv1's and sv4's scans can form the consensus.
        val expectedScanUris = Set("http://localhost:5012", "http://localhost:5312")
        def bftReadLogged(subject: String) =
          forAtLeast(1, logs) { log =>
            val prefix =
              s"Obtained the $subject for round $round via BFT read from scans: "
            log.loggerName should include("SV=sv2")
            log.message should include(prefix)
            val scanUris = log.message
              .substring(log.message.indexOf(prefix) + prefix.length)
              .stripSuffix(".")
              .split(", ")
              .toSeq
            scanUris.size should be(1)
            forAll(scanUris)(uri => expectedScanUris should contain(uri))
          }
        bftReadLogged("root-hash")
        bftReadLogged("reward accounting totals")
      },
    )
  }

  private def metricValue(
      node: LocalInstanceReference,
      name: String,
      labels: Map[String, String],
  ): Long =
    node.metrics
      .get(s"$MetricsPrefix.$name", labels)
      .select[MetricValue.LongPoint]
      .value
      .value

  // Here we confirm that sv2 can do BFT read of root-hash and batch from sv1 and sv4 only
  // And the rewards processing works even when sv3 is offline.
  private def confirmBftRead(
      bobParty: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue("Stop sv3 to confirm we can perform bft read from sv1, sv4 only") {
      sv3Backend.stop()
      sv3ScanBackend.stop()
    }

    // sv2 will process all ProcessRewardsV2
    val otherProcessRewardsTriggers =
      Seq(sv1Backend, sv4Backend).map(_.dsoDelegateBasedAutomation.trigger[ProcessRewardsTrigger])

    otherProcessRewardsTriggers.foreach(_.pause().futureValue)

    try {
      val sv2CalculateRewards = sv2Backend.dsoAutomation.trigger[CalculateRewardsTrigger]

      // Pausing this ensures that the root-hash is not calculated while we advance round
      val sv2RewardComputation = sv2ScanBackend.automation.trigger[RewardComputationTrigger]

      val round = oldestOpenRound
      withExpectedRewardTriggersLogging(round) {
        // Here we ensure that SV2 has done ingestion of app-activity for the round just closed
        // But then its AppActivityRecordMetaT is bumped so that it cannot compute the
        // root-hash for the round.
        val calculateRewardsCid = setTriggersWithin(
          triggersToPauseAtStart = Seq(sv2CalculateRewards, sv2RewardComputation)
        ) {
          doTransfer(bobParty)
          // Note: we can't use advanceRoundsToNextRoundOpening here, as it blocks
          // on summarizing and issuing round to complete, and here the
          // summarizing round will block until the sv2 provides the round totals
          // via bft read.
          advanceTimeAndWaitForRoundOpening

          val (calculateRewardsCid, rootHash) =
            clue(
              s"Round $round just closed: its CalculateRewardsV2 exists and sv1 serves root-hash"
            ) {
              eventually() {
                val calc = sv1Backend.appState.dsoStore
                  .listCalculateRewardsV2()
                  .futureValue
                  .filterNot(_.payload.dryRun)
                  .find(_.payload.round.number == round)
                  .value
                val rootHash = inside(sv1ScanBackend.getRewardAccountingRootHash(round)) {
                  case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(h) =>
                    h.rootHash
                }
                (calc.contractId, rootHash)
              }
            }

          clue(s"Only sv1 and sv4 confirm round $round, so it is not yet processed") {
            eventually() {
              val startProcessingAction = new ARC_AmuletRules(
                new CRARC_StartProcessingRewardsV2(
                  new AmuletRules_StartProcessingRewardsV2(calculateRewardsCid, new Hash(rootHash))
                )
              )
              sv1Backend.appState.dsoStore
                .listConfirmations(startProcessingAction)
                .futureValue should have size 2
            }
            sv1Backend.appState.dsoStore
              .listOldestSummarizingMiningRounds()
              .futureValue
              .map(_.payload.round.number) should contain(round)
          }

          // This is trying to simulate AppActivityRecordMetaT's userVersion bump
          // albeit in a direct way, to avoid restart of scan app, etc.
          actAndCheck(
            s"Reset sv2's earliest-ingested round to $round", {
              val sv2Db = sv2ScanBackend.appState.storage match {
                case db: DbStorage => db
                case other => fail(s"Expected DbStorage")
              }
              implicit val closeContext: CloseContext = CloseContext(sv2Db)
              sv2Db
                .update_(
                  sqlu"""update app_activity_record_meta
                         set earliest_ingested_round = $round,
                             last_archived_round = null""",
                  "test.increaseAppActivityMeta_EarliestIngestedRound",
                )
                .futureValueUS
            },
          )(
            s"sv2's own scan now answers CannotProvide for round $round",
            _ =>
              sv2ScanBackend.getRewardAccountingRootHash(round) shouldBe
                a[GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashCannotProvide],
          )

          calculateRewardsCid
        }

        // setTriggersWithin has resumed sv2's CalculateRewardsTrigger. sv3 is stopped and sv2's own
        // scan CannotProvide, so the deciding 3rd confirmation can only come from sv2 via bft read.
        clue(s"sv2's own scan still answers CannotProvide for round $round") {
          sv2ScanBackend.getRewardAccountingRootHash(round) shouldBe
            a[GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashCannotProvide]
        }

        clue(
          s"sv2 reads round $round from the sv1 and sv4, and supplies the 3rd confirmation vote"
        ) {
          eventually() {
            sv1Backend.appState.dsoStore
              .listCalculateRewardsV2()
              .futureValue
              .map(_.contractId) should not contain calculateRewardsCid
          }
        }
      }

      clue(
        s"sv2 does processing of ProcessRewardsV2"
      ) {
        eventually() {
          sv1Backend.appState.dsoStore
            .listRewardCouponsV2()
            .futureValue
            .map(_.payload.round.number) should contain(round)
          sv1Backend.appState.dsoStore
            .listProcessRewardsV2()
            .futureValue
            .map(_.payload.round.number) should not contain round
        }
      }

      clue(
        "sv2's BFT-read counters incremented, as it obtained both root-hash and batch via BFT read"
      ) {
        // metrics.get can throw before the meter is first marked, so retry.
        eventually(retryOnTestFailuresOnly = false) {
          metricValue(
            sv2Backend,
            "calculate_rewards_v2.root_hash_bft_reads",
            Map("dryRun" -> "false"),
          ) should be >= 1L
          metricValue(
            sv2Backend,
            "process_rewards_v2.batch_bft_reads",
            Map("dryRun" -> "false"),
          ) should be >= 1L
        }
      }
    } finally {
      otherProcessRewardsTriggers.foreach(_.resume())
      // On restart, sv3 catches up on the round that was processed while sv3
      // was down. The reward triggers may fire before that round
      // advances. sv2's own scan still answers 'CannotProvide' for that round
      // (its earliest-ingested round was bumped above), so it contributes an
      // 'IgnoreResponse' to sv3's BFT reads, which 'BftScanConnection' logs at
      // WARN as "The following Scan URLs disagreed with consensus". These WARNs
      // are an expected consequence of the 'CannotProvide' scenario under test,
      // so we suppress them (targeted to 'BftScanConnection' WARNs) to keep the
      // `sbt checkErrors` log-scan gate green.
      //
      // The same supression happens in 'withExpectedRewardTriggersLogging' but
      //
      // 1. 'withExpectedRewardTriggersLogging' targets a narrow part of the try
      //     block and doesn't expand into this finally block, and
      // 2. 'withExpectedRewardTriggersLogging' has strict expectation about the
      //     logs when rewards trigger fire. Here triggers may or may not fire
      //     -- it's a race between sv3 catching up and triggers firing. We
      //     can't guarantee that triggers fire => can't expect that WARNs will
      //     appear. So we just supress them instead of expecting them.
      loggerFactory.suppress(
        SuppressionRule.forLogger[BftScanConnection] && SuppressionRule.Level(Level.WARN)
      ) {
        clue("Restart sv3") {
          sv3ScanBackend.start()
          sv3Backend.start()
          sv3Backend.waitForInitialization(
            timeout = NonNegativeDuration.tryFromDuration(120.seconds)
          )
          sv3ScanBackend.waitForInitialization(
            timeout = NonNegativeDuration.tryFromDuration(120.seconds)
          )
        }
      }
    }
  }

  // If an SV submits a root-hash different from others, the mismatch in the
  // 'Confirmation' must be flagged by all SVs.
  // This test also confirms that reward processing works in prescence of 'f' mismatches.
  // for n=4, f=1, so one faulty vote is tolerated.
  private def confirmMismatchingRootHashIsFlagged(
      bobParty: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val honestBackends = Seq(sv1Backend, sv3Backend, sv4Backend)
    val allBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
    val honestConfirmationTriggers =
      honestBackends.map(_.dsoAutomation.trigger[CalculateRewardsTrigger]) ++
        honestBackends.map(_.dsoAutomation.trigger[CalculateRewardsDryRunTrigger])
    val sv2RewardComputation = sv2ScanBackend.automation.trigger[RewardComputationTrigger]
    val confirmationMismatchTriggers =
      allBackends.map(_.dsoAutomation.trigger[ConfirmationMismatchReportTrigger])

    val round = oldestOpenRound
    val sv2Db = sv2ScanBackend.appState.storage match {
      case db: DbStorage => db
      case _ => fail("Expected DbStorage")
    }
    implicit val closeContext: CloseContext = CloseContext(sv2Db)

    var calculateRewardsCid: Option[CalculateRewardsV2.ContractId] = None
    var dryRunCalculateRewardsCid: Option[CalculateRewardsV2.ContractId] = None

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.WARN))(
      {
        val (cid, dryRunCid) =
          setTriggersWithin(triggersToPauseAtStart = honestConfirmationTriggers) {

            setTriggersWithin(triggersToPauseAtStart = Seq(sv2RewardComputation)) {
              doTransfer(bobParty)
              advanceRoundsToNextRoundOpening
              doTransfer(bobParty)

              val sv2HistoryId = sv2ScanBackend.appState.eventStore.updateHistory.historyId
              clue(s"sv2 has ingested activity for round ${round + 1}") {
                eventually() {
                  val ingestedRound1Count = sv2Db
                    .querySingle(
                      sql"""select count(*) from app_activity_record_store
                      where round_number = ${round + 1} and history_id = $sv2HistoryId"""
                        .as[Int]
                        .headOption,
                      "test.countIngestedAppActivityRecords",
                    )
                    .value
                    .futureValueUS
                  ingestedRound1Count.value should be > 0
                }
              }

              clue(s"Perturb sv2's app activity weights for round $round") {
                sv2Db
                  .update_(
                    sqlu"""update app_activity_record_store
                     set app_activity_weights = array(
                       select weight * 1.1
                       from unnest(app_activity_weights) with ordinality as t(weight, ord)
                       order by ord
                     )
                     where round_number = $round and history_id = $sv2HistoryId""",
                    "test.perturbAppActivityWeights",
                  )
                  .futureValueUS
              }
            }

            // sv2's reward computation has resumed.
            // honestBackends must have processed the root-hash, as they were not paused
            val (calculateRewardsCid, dryRunCalculateRewardsCid, correctRootHash) =
              clue(
                s"Round $round's CalculateRewardsV2 contracts exist, and rootHash processed by sv1"
              ) {
                eventually() {
                  val v2s = sv1Backend.appState.dsoStore
                    .listCalculateRewardsV2()
                    .futureValue
                    .filter(_.payload.round.number == round)
                  val regularCid = v2s.find(c => !c.payload.dryRun).value.contractId
                  val dryRunCid = v2s.find(_.payload.dryRun).value.contractId
                  val rootHash = inside(sv1ScanBackend.getRewardAccountingRootHash(round)) {
                    case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(
                          h
                        ) =>
                      h.rootHash
                  }
                  (regularCid, dryRunCid, rootHash)
                }
              }

            val sv2RootHash =
              clue(
                s"sv2's own scan computes a root-hash for round $round that differs from the honest one"
              ) {
                eventually() {
                  val hash = inside(sv2ScanBackend.getRewardAccountingRootHash(round)) {
                    case GetRewardAccountingRootHashResponse.members.RewardAccountingRootHashOk(
                          h
                        ) =>
                      h.rootHash
                  }
                  hash should not equal correctRootHash
                  hash
                }
              }
            val sv2ConfirmationAction = new ARC_AmuletRules(
              new CRARC_StartProcessingRewardsV2(
                new AmuletRules_StartProcessingRewardsV2(calculateRewardsCid, new Hash(sv2RootHash))
              )
            )
            val sv2DryRunConfirmationAction = new ARC_AmuletRules(
              new CRARC_StartProcessingRewardsV2(
                new AmuletRules_StartProcessingRewardsV2(
                  dryRunCalculateRewardsCid,
                  new Hash(sv2RootHash),
                )
              )
            )

            clue(s"sv2 has cast confirmations for round $round") {
              eventually() {
                sv1Backend.appState.dsoStore
                  .listConfirmations(sv2ConfirmationAction)
                  .futureValue should have size 1
                sv1Backend.appState.dsoStore
                  .listConfirmations(sv2DryRunConfirmationAction)
                  .futureValue should have size 1
              }
            }

            (calculateRewardsCid, dryRunCalculateRewardsCid)
          }

        calculateRewardsCid = Some(cid)
        dryRunCalculateRewardsCid = Some(dryRunCid)

        // honestConfirmationTriggers have resumed, and we should observe mismatch in confirmations
        clue("the honest majority processes both CalculateRewardsV2") {
          eventually() {
            val remaining = sv1Backend.appState.dsoStore
              .listCalculateRewardsV2()
              .futureValue
              .map(_.contractId)
            remaining should not contain cid
            remaining should not contain dryRunCid
          }
        }

        // Pause here so no further mismatch reports are logged past this point causing flake.
        confirmationMismatchTriggers.foreach(_.pause().futureValue)
      },
      logs => {
        // Every SV's ConfirmationMismatchReportTrigger flags sv2's mismatch in confirmation, for both
        // the regular and the dry-run round.
        forAll(allBackends) { sv =>
          forAll(Seq(calculateRewardsCid.value, dryRunCalculateRewardsCid.value)) { cid =>
            forAtLeast(1, logs) { entry =>
              entry.loggerName should include(s"SV=${sv.name}")
              entry.warningMessage should (include(
                "has a mismatch with confirmations"
              ) and include(
                cid.contractId
              ))
            }
          }
        }
      },
    )
  }

  private def advanceRoundsAndConfirmAllPriorRoundsPruned()(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val sv1Db = sv1ScanBackend.appState.storage match {
      case db: DbStorage => db
      case _ => fail("Expected DbStorage")
    }
    val sv2Db = sv2ScanBackend.appState.storage match {
      case db: DbStorage => db
      case _ => fail("Expected DbStorage")
    }
    val sv1HistoryId = sv1ScanBackend.appState.eventStore.updateHistory.historyId
    val sv2HistoryId = sv2ScanBackend.appState.eventStore.updateHistory.historyId
    val sv1RewardsRefStore = sv1ScanBackend.appState.rewardsReferenceStore
    val sv2RewardsRefStore = sv2ScanBackend.appState.rewardsReferenceStore
    val sv1PruneTrigger = sv1ScanBackend.automation.trigger[PruneRewardAccountingTrigger]
    val sv2PruneTrigger = sv2ScanBackend.automation.trigger[PruneRewardAccountingTrigger]

    def hasUnprunedRewardAccountingDataBelow(
        db: DbStorage,
        historyId: Long,
        upperExclusive: Long,
    ): Boolean = {
      implicit val closeContext: CloseContext = CloseContext(db)
      db
        .querySingle(
          sql"""select
                  exists(select 1 from app_activity_party_totals
                         where history_id = $historyId and round_number < $upperExclusive)
                  or exists(select 1 from app_activity_round_totals
                         where history_id = $historyId and round_number < $upperExclusive)
                  or exists(select 1 from app_reward_party_totals
                         where history_id = $historyId and round_number < $upperExclusive)
                  or exists(select 1 from app_reward_round_totals
                         where history_id = $historyId and round_number < $upperExclusive)
                  or exists(select 1 from app_reward_batch_hashes
                         where history_id = $historyId and round_number < $upperExclusive)
                  or exists(select 1 from app_reward_root_hashes
                         where history_id = $historyId and round_number < $upperExclusive)
             """.as[Boolean].headOption,
          "test.hasUnprunedRewardAccountingDataBelow",
        )
        .value
        .futureValueUS
        .value
    }

    def hasUnprunedArchiveDataForRound(
        store: ScanRewardsReferenceStore,
        roundNumber: Long,
    ): Boolean =
      store.lookupArchivedAtForOpenMiningRound(roundNumber).futureValue.isDefined

    // The trigger prunes at most one round per invocation, so it is run until it
    // reports that there is nothing left to prune.
    def pruneUntilNothingLeftToPrune(trigger: PruneRewardAccountingTrigger): Unit = {
      @tailrec def go(runs: Int): Unit = {
        runs should be < 30
        if (trigger.runOnce().futureValue) go(runs + 1) else ()
      }
      go(0)
    }

    def confirmFullyPruned(
        db: DbStorage,
        historyId: Long,
        store: ScanRewardsReferenceStore,
        upperExclusive: Long,
    ): Unit = {
      hasUnprunedRewardAccountingDataBelow(db, historyId, upperExclusive) shouldBe false
      hasUnprunedArchiveDataForRound(store, upperExclusive - 1) shouldBe false
    }

    def confirmPruningMetrics(
        scan: LocalInstanceReference,
        atLeastRound: Long,
    ): Unit = {
      def pruningMetricValue(name: String, labels: Map[String, String] = Map.empty): Long =
        metricValue(scan, s"scan.reward_accounting_pruning.$name", labels)

      eventually() {
        pruningMetricValue("pruned_round") should be >= atLeastRound
        forAll(
          Seq(
            "scan_rewards_reference_store_archived",
            "app_activity_round_totals",
            "app_reward_round_totals",
          )
        ) { table =>
          pruningMetricValue("deleted_rows", Map("table" -> table)) should be > 0L
        }
      }
    }

    setTriggersWithin(triggersToPauseAtStart = Seq(sv1PruneTrigger, sv2PruneTrigger)) {
      // Simulate SV2 scan ingestion lag and confirm that pruning does not happen
      // until the ingestion has caught up.
      val newLowestOpen = pauseScanVerdictIngestionWithin(sv2ScanBackend) {
        advanceRoundsToNextRoundOpening
        val newLowestOpen = oldestOpenRound

        // We need the archived_at of newLowestOpen + 1 to be lower than the
        // the active open round's openAt.
        // So advancing by 3 rounds is a safe way to achieve this.
        (1 to 3).foreach(_ => advanceRoundsToNextRoundOpening)

        clue(s"sv1 retains rounds below $newLowestOpen while within the retention period") {
          pruneUntilNothingLeftToPrune(sv1PruneTrigger)
          hasUnprunedRewardAccountingDataBelow(sv1Db, sv1HistoryId, newLowestOpen) shouldBe true
          hasUnprunedArchiveDataForRound(sv1RewardsRefStore, newLowestOpen - 1) shouldBe true
        }

        advanceTime(rewardAccountingRetentionPeriod.asJava)

        clue(s"sv1 prunes rounds below $newLowestOpen once past the retention period") {
          // Retried, as a round only becomes prunable once the reference store has
          // ingested the archival of all of its reward-accounting contracts.
          eventually() {
            pruneUntilNothingLeftToPrune(sv1PruneTrigger)
            confirmFullyPruned(sv1Db, sv1HistoryId, sv1RewardsRefStore, newLowestOpen)
          }
          confirmPruningMetrics(sv1ScanBackend, newLowestOpen - 1)
        }

        clue(
          s"sv2 does not prune rounds below $newLowestOpen while its verdict ingestion is paused"
        ) {
          pruneUntilNothingLeftToPrune(sv2PruneTrigger)
          hasUnprunedArchiveDataForRound(sv2RewardsRefStore, newLowestOpen - 1) shouldBe true
        }

        newLowestOpen
      }

      clue(s"sv2 eventually prunes data once verdict ingestion resumes") {
        // Because of the delay in catchup of the verdict ingestion
        // this can occasionally take longer than the default 20s eventually window
        eventually(90.seconds) {
          pruneUntilNothingLeftToPrune(sv2PruneTrigger)
          confirmFullyPruned(sv2Db, sv2HistoryId, sv2RewardsRefStore, newLowestOpen)
        }
        confirmPruningMetrics(sv2ScanBackend, newLowestOpen - 1)
      }
    }
  }

  private def doTransfer(
      bobParty: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val offerCid = aliceWalletClient.createTransferOffer(
      bobParty,
      BigDecimal(10.0),
      "activity",
      CantonTimestamp.now().plus(Duration.ofMinutes(1)),
      s"transfer-${scala.util.Random.nextInt()}",
    )
    bobWalletClient.acceptTransferOffer(offerCid)
  }

  private def walkBatch(
      round: Long,
      hash: String,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Seq[definitions.RewardAccountingMintingAllowance] =
    sv1ScanBackend.getRewardAccountingBatch(round, hash).toList.flatMap {
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfBatches(b) =>
        b.childHashes.flatMap(h => walkBatch(round, h))
      case GetRewardAccountingBatchResponse.members.RewardAccountingBatchOfMintingAllowances(b) =>
        b.mintingAllowances.toSeq
    }

  private def oldestOpenRound(implicit env: SpliceTestConsoleEnvironment): Long = {
    val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
    openRounds.map(_.contract.payload.round.number.toLong).min
  }

  private def assertOldestOpenRound(
      expected: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"Asserting oldest open round=$expected") {
      eventually() {
        val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val roundNumbers = openRounds.map(_.contract.payload.round.number.toLong).sorted
        roundNumbers should have size 3
        roundNumbers.head shouldBe expected
      }
    }
  }

  private def changeRewardConfig(
      enableDryRun: Boolean,
      rewardVersion: RewardVersion = RewardVersion.REWARDVERSION_FEATUREDAPPMARKERS,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val amuletRules = sv1Backend.getDsoInfo().amuletRules
    val existing = AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)
    val rc = existing.rewardConfig.get()
    val newRc = new RewardConfig(
      rewardVersion,
      if (enableDryRun) Optional.of(RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS)
      else Optional.empty[RewardVersion](),
      rc.batchSize,
      rc.rewardCouponTimeToLive,
      rc.appRewardCouponThreshold,
    )
    val newConfig = new AmuletConfig[USD](
      existing.transferConfig,
      existing.issuanceCurve,
      existing.decentralizedSynchronizer,
      existing.tickDuration,
      existing.packageConfig,
      existing.transferPreapprovalFee,
      existing.featuredAppActivityMarkerAmount,
      existing.optDevelopmentFundManager,
      existing.externalPartyConfigStateTickDuration,
      Optional.of(newRc),
      existing.transferPreapprovalBaseDuration,
      existing.developmentFundManagerBlacklist,
      existing.minDevelopmentFundMintingDelay,
      existing.amuletSwitchOverTimes,
    )
    setAmuletConfig(Seq((None, newConfig, existing)))
    eventually() {
      sv1Backend.listVoteRequests() shouldBe empty
    }
  }

}
