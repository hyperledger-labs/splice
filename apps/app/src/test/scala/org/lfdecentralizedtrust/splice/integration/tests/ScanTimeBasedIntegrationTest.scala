package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsEntry
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.automation.ScanAggregationTrigger
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultAnsConfig

import java.time.Duration
import scala.jdk.CollectionConverters.*

class ScanTimeBasedIntegrationTest
    extends IntegrationTest
    with AmuletConfigUtil
    with WalletTestUtil
    with TimeTestUtil {

  val initialRound = 4815L

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // The wallet automation periodically merges amulets, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      // Start ScanAggregationTrigger in paused state, calling runOnce in tests
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.withPausedTrigger[ScanAggregationTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialRound = initialRound)
        )(config)
      )
      .withAdditionalSetup { implicit env =>
        // start at a point where the reward trigers can run so that we avoid warnings about missed rewards
        advanceTimeForRewardAutomationToRunForCurrentRound
      }

  def firstRound(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Long =
    sv1ScanBackend.getDsoInfo().initialRound match {
      case None => 0L
      case Some(round) => round.toLong
    }

  "report correct reference data" in { implicit env =>
    def roundNum() =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number
    roundNum() shouldBe firstRound + 1

    advanceRoundsToNextRoundOpening
    roundNum() shouldBe firstRound + 2
  }

  "return correct amulet configs" in { implicit env =>
    // TODO(DACH-NY/canton-network-node#2930) test also with changing amulet prices.
    // TODO(DACH-NY/canton-network-node#2930) Currently we are not guaranteed that the first three rounds are correctly
    // captured in the tx log, so for now we first advance a round, and query only on that
    // round and beyond. Once that is fixed, we should make sure that querying for round 0 is reliable as well.

    advanceRoundsToNextRoundOpening

    clue(s"Get config for round ${firstRound + 3}") {
      val cfg = eventuallySucceeds() {
        sv1ScanBackend.getAmuletConfigForRound(firstRound + 3)
      }
      cfg.amuletCreateFee.bigDecimal.setScale(10) should be(
        SpliceUtil.defaultCreateFee.fee divide walletAmuletPrice setScale 10
      )
      cfg.holdingFee.bigDecimal.setScale(10) should be(
        SpliceUtil.defaultHoldingFee.rate divide walletAmuletPrice setScale 10
      )
      cfg.lockHolderFee.bigDecimal.setScale(10) should be(
        SpliceUtil.defaultLockHolderFee.fee divide walletAmuletPrice setScale 10
      )
      cfg.transferFee.initial.bigDecimal.setScale(10) should be(
        SpliceUtil.defaultTransferFee.initialRate.setScale(10)
      )
      cfg.transferFee.steps shouldBe (
        SpliceUtil.defaultTransferFee.steps.asScala.toSeq.map(step =>
          HttpScanAppClient.RateStep(
            step._1 divide walletAmuletPrice,
            step._2,
          )
        )
      )
    }

    clue(s"Try to get config for round ${firstRound + 4} which does not yet exist") {
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.getAmuletConfigForRound(firstRound + 4),
        _.errorMessage should include(s"Round ${firstRound + 4} not found"),
      )
    }

    val newHoldingFee = BigDecimal(0.1)
    clue("schedule a config change, and advance time for it to take effect") {
      val amuletRules = sv1ScanBackend.getAmuletRules().contract
      val configs = Seq(
        (
          Some(Duration.ofSeconds(10)),
          mkUpdatedAmuletConfig(amuletRules, defaultTickDuration, holdingFee = newHoldingFee),
          amuletRules.payload.configSchedule.initialValue,
        )
      )

      setAmuletConfig(configs)
      advanceTime(Duration.ofSeconds(20))
      eventually() {
        BigDecimal(
          sv1ScanBackend
            .getAmuletRules()
            .payload
            .configSchedule
            .initialValue
            .transferConfig
            .holdingFee
            .rate
        ) should be(newHoldingFee)
      }
      advanceRoundsToNextRoundOpening
    }
    clue(s"Round ${firstRound + 4} should now be open, and have the new configuration") {
      eventuallySucceeds() {
        sv1ScanBackend.getAmuletConfigForRound(firstRound + 4).holdingFee should be(
          walletUsdToAmulet(newHoldingFee)
        )
      }
    }
  }

  "support app and validator leaderboards" in { implicit env =>
    val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
    waitForWalletUser(aliceValidatorWalletClient)
    waitForWalletUser(bobValidatorWalletClient)

    clue("Tap to get some amulets") {
      aliceWalletClient.tap(500.0)
      bobWalletClient.tap(500.0)
      aliceValidatorWalletClient.tap(100.0)
      bobValidatorWalletClient.tap(100.0)
    }
    clue("No aggregate round data should be available yet")({
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.getRoundOfLatestData(),
        _.errorMessage should include("No data has been made available yet"),
      )
    })
    clue("Transfer some CC, to generate reward coupons")({
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, 100.0)
    })
    clue(
      "Advance a round and generate some more reward coupons - this time with alice's validator being featured"
    )({
      advanceRoundsToNextRoundOpening
      grantFeaturedAppRight(aliceValidatorWalletClient)
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 41.0)
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, 101.0)
    })
    clue("Advance 2 ticks for the first coupons to be collectable")({
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
    })
    clue("Alice's and Bob's validators use their app&validator rewards when transfering CC")({
      p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0)
      p2pTransfer(bobValidatorWalletClient, aliceWalletClient, aliceUserParty, 10.0)
    })
    clue(
      s"Some more transfers collect more rewards in round ${firstRound + 5} (issued in round ${firstRound + 1})"
    )({
      advanceRoundsToNextRoundOpening
      p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0)
      p2pTransfer(bobValidatorWalletClient, aliceWalletClient, aliceUserParty, 10.0)
    })
    val baseRoundWithLatestData = clue(
      "Advance 1 more tick to make sure we capture at least one round change in the tx history"
    ) {
      advanceRoundsToNextRoundOpening
      sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
      sv1ScanBackend.getRoundOfLatestData()._1
    }
    clue("Advance one more tick to get to the next closed round") {
      advanceRoundsToNextRoundOpening
      val ledgerTime = getLedgerTime.toInstant
      val expectedLastRound = baseRoundWithLatestData + 1
      sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
      sv1ScanBackend.getRoundOfLatestData() shouldBe (expectedLastRound, ledgerTime)
      sv1ScanBackend.getAggregatedRounds().value shouldBe ScanAggregator.RoundRange(
        firstRound,
        expectedLastRound,
      )
    }
    clue("Data for a later round does not yet exist")({
      val lastAggregatedRound = sv1ScanBackend.getRoundOfLatestData()._1
      val laterRound = lastAggregatedRound + 1
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.getTopProvidersByAppRewards(
          laterRound,
          10,
        ),
        _.errorMessage should include(s"Data for round $laterRound not yet computed"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.getTopValidatorsByValidatorRewards(laterRound, 10),
        _.errorMessage should include(s"Data for round $laterRound not yet computed"),
      )
    })

    def walletClientParty(walletClient: WalletAppClientReference) =
      Codec.decode(Codec.Party)(walletClient.userStatus().party).value
    val latestRound = baseRoundWithLatestData + 4
    actAndCheck(
      "Advance four more rounds, for the previous rounds to close (where rewards were collected)",
      Range(0, 3).foreach(_ => advanceRoundsToNextRoundOpening),
    )(
      s"Test leaderboards for latest rounds ${latestRound}",
      _ => {
        val ledgerTime = getLedgerTime.toInstant
        sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
        sv1ScanBackend.getRoundOfLatestData() should be((latestRound, ledgerTime))

        val aliceValidatorWalletClientParty =
          walletClientParty(aliceValidatorWalletClient).toProtoPrimitive
        val bobValidatorWalletClientParty =
          walletClientParty(bobValidatorWalletClient).toProtoPrimitive

        clue("Compare leaderboard getTopProvidersByAppRewards latestRound") {
          sv1ScanBackend
            .listRoundPartyTotals(firstRound, latestRound)
            .map { rpt =>
              // only keeps latest closed round and app rewards for that round per party
              rpt.party -> (rpt.closedRound, BigDecimal(rpt.cumulativeAppRewards))
            }
            .filter { case (p, (cr, appRewards)) =>
              appRewards > 0 && (p == aliceValidatorWalletClientParty || p == bobValidatorWalletClientParty) && cr == latestRound
            }
            .sortBy(_._2._2)(Ordering.BigDecimal.reverse)
            .map(_._1) should contain theSameElementsInOrderAs (Seq(
            bobValidatorWalletClientParty,
            aliceValidatorWalletClientParty,
          ))
          sv1ScanBackend
            .getTopProvidersByAppRewards(latestRound, 10)
            .map(_._1.toProtoPrimitive) shouldBe (Seq(
            bobValidatorWalletClientParty,
            aliceValidatorWalletClientParty,
          ))
        }
        clue("Compare leaderboard getTopValidatorsByValidatorRewards latestRound") {
          sv1ScanBackend
            .listRoundPartyTotals(firstRound, latestRound)
            .map { rpt =>
              // only keeps latest closed round and app rewards for that round per party
              rpt.party -> (rpt.closedRound, BigDecimal(rpt.cumulativeValidatorRewards))
            }
            .filter { case (p, (cr, validatorRewards)) =>
              validatorRewards > 0 && (p == aliceValidatorWalletClientParty || p == bobValidatorWalletClientParty) && cr == latestRound
            }
            .sortBy(_._2._2)(Ordering.BigDecimal.reverse)
            .map(_._1) should contain theSameElementsInOrderAs (Seq(
            bobValidatorWalletClientParty,
            aliceValidatorWalletClientParty,
          ))

          sv1ScanBackend
            .getTopValidatorsByValidatorRewards(latestRound, 10)
            .map(_._1.toProtoPrimitive) should contain theSameElementsInOrderAs (Seq(
            bobValidatorWalletClientParty,
            aliceValidatorWalletClientParty,
          ))
        }
      },
    )
  }

  "get total amulet balance" in { implicit env =>
    val (_, _) = onboardAliceAndBob()

    def roundNum() =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number
    roundNum() shouldBe (firstRound + 1)

    val tapRound1Amount = BigDecimal(500.0)
    clue(s"Tap in round ${firstRound + 1}") {
      aliceWalletClient.tap(tapRound1Amount)
    }

    advanceRoundsToNextRoundOpening

    roundNum() shouldBe (firstRound + 2)

    val tapRound2Amount = BigDecimal(500.0)
    clue(s"Tap in round ${firstRound + 2}") {
      bobWalletClient.tap(tapRound2Amount)
    }

    actAndCheck(
      s"advance to close round ${firstRound + 2}",
      (0 to 4).foreach(_ => advanceRoundsToNextRoundOpening),
    )(
      s"check round ${firstRound + 2} is closed",
      _ => {
        sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
        sv1ScanBackend.getRoundOfLatestData()._1 shouldBe firstRound + 2
        sv1ScanBackend.getAggregatedRounds().value shouldBe ScanAggregator.RoundRange(
          firstRound + 0,
          firstRound + 2,
        )
      },
    )

    clue(
      s"Get total balances for round ${firstRound + 0}, ${firstRound + 1} and ${firstRound + 2}"
    ) {
      val total0 =
        sv1ScanBackend
          .getTotalAmuletBalance(firstRound + 0)
          .getOrElse(BigDecimal(0))
      val total1 =
        sv1ScanBackend
          .getTotalAmuletBalance(firstRound + 1)
          .getOrElse(BigDecimal(0))
      val total2 =
        sv1ScanBackend
          .getTotalAmuletBalance(firstRound + 2)
          .valueOrFail("Amulet balance not yet computed")

      val holdingFeeAfterOneRound = 1 * defaultHoldingFeeAmulet
      val holdingFeeAfterTwoRounds = 2 * defaultHoldingFeeAmulet

      total0 shouldBe 0.0
      total1 shouldBe (walletUsdToAmulet(tapRound1Amount) - holdingFeeAfterOneRound)
      total2 shouldBe (
        walletUsdToAmulet(tapRound1Amount) - holdingFeeAfterTwoRounds +
          walletUsdToAmulet(tapRound2Amount) - holdingFeeAfterOneRound
      )
      sv1ScanBackend.getAggregatedRounds().value shouldBe ScanAggregator.RoundRange(
        firstRound + 0,
        firstRound + 2,
      )
      sv1ScanBackend
        .listRoundTotals(firstRound + 0, firstRound + 2)
        .map(rt => (rt.closedRound, BigDecimal(rt.totalAmuletBalance))) shouldBe List(
        firstRound + 0L -> total0,
        firstRound + 1L -> total1,
        firstRound + 2L -> total2,
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundTotals(firstRound + 1, firstRound + 3),
        _.errorMessage should include("is outside of the available rounds range"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundPartyTotals(firstRound + 1, firstRound + 3),
        _.errorMessage should include("is outside of the available rounds range"),
      )
    }
  }

  "Not get aggregates for incorrect ranges" in { implicit env =>
    clue("Try to get round totals for negative rounds") {
      val startRounds = List(-1L, 0L, -1L, 1L, -1L)
      val endRounds = List(1L, -1L, 0L, -1L, -1L)
      startRounds.zip(endRounds).foreach { case (start, end) =>
        assertThrowsAndLogsCommandFailures(
          sv1ScanBackend.listRoundTotals(start, end),
          _.errorMessage should include(
            s"rounds must be non-negative: start_round $start, end_round $end"
          ),
        )
        assertThrowsAndLogsCommandFailures(
          sv1ScanBackend.listRoundPartyTotals(start, end),
          _.errorMessage should include(
            s"rounds must be non-negative: start_round $start, end_round $end"
          ),
        )
      }
    }
    clue("Try to get round totals for range where end is smaller than start") {
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundTotals(firstRound + 10, firstRound + 9),
        _.errorMessage should include(
          s"end_round ${firstRound + 9} must be >= start_round ${firstRound + 10}"
        ),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundPartyTotals(firstRound + 10, firstRound + 9),
        _.errorMessage should include(
          s"end_round ${firstRound + 9} must be >= start_round ${firstRound + 10}"
        ),
      )
    }
    clue("Try to get too many round totals or round party totals") {
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundTotals(firstRound + 0, firstRound + 200),
        _.errorMessage should include(s"Cannot request more than 200 rounds at a time"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundTotals(firstRound + 1, firstRound + 201),
        _.errorMessage should include(s"Cannot request more than 200 rounds at a time"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundPartyTotals(firstRound + 0, firstRound + 50),
        _.errorMessage should include(s"Cannot request more than 50 rounds at a time"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundPartyTotals(firstRound + 1, firstRound + 51),
        _.errorMessage should include(s"Cannot request more than 50 rounds at a time"),
      )
    }
  }

  "return AnsRules contract and config" in { implicit env =>
    val ansRules = sv1ScanBackend.getAnsRules()
    ansRules.payload.config shouldBe defaultAnsConfig()
  }

  "snapshotting" in { implicit env =>
    val (aliceUserParty, _) = onboardAliceAndBob()
    val migrationId = sv1ScanBackend.config.domainMigrationId

    clue(
      "Wait for backfilling to complete, as the ACS snapshot trigger is paused until then"
    ) {
      eventually() {
        sv1ScanBackend.automation.store.updateHistory
          .getBackfillingState()
          .futureValue should be(BackfillingState.Complete)
        advanceTime(sv1ScanBackend.config.automation.pollingInterval.asJava)
      }
    }

    val snapshotBefore = sv1ScanBackend.getDateOfMostRecentSnapshotBefore(
      getLedgerTime,
      migrationId,
    )

    createAnsEntry(
      aliceAnsExternalClient,
      perTestCaseName("snapshot"),
      aliceWalletClient,
      tapAmount = 5000,
    )

    advanceTime(
      java.time.Duration
        .ofHours(sv1ScanBackend.config.acsSnapshotPeriodHours.toLong)
        .plusSeconds(1L)
    )

    val snapshotAfter = eventually() {
      val snapshotAfter = sv1ScanBackend.getDateOfMostRecentSnapshotBefore(
        getLedgerTime,
        migrationId,
      )
      snapshotBefore should not(be(snapshotAfter))
      snapshotAfter
    }

    val snapshotAfterData = sv1ScanBackend.getAcsSnapshotAt(
      CantonTimestamp.assertFromInstant(snapshotAfter.value.toInstant),
      migrationId,
      templates = Some(
        Vector(
          PackageQualifiedName.fromJavaCodegenCompanion(Amulet.COMPANION),
          PackageQualifiedName.fromJavaCodegenCompanion(AnsEntry.COMPANION),
        )
      ),
      partyIds = Some(Vector(aliceUserParty)),
    )

    inside(snapshotAfterData) { case Some(data) =>
      val (entries, coins) =
        data.createdEvents.partition(
          _.templateId.contains(QualifiedName(AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID).toString)
        )
      val entry = AnsEntry
        .jsonDecoder()
        .decode(new JsonLfReader(entries.loneElement.createArguments.noSpaces))
      entry.name shouldBe perTestCaseName("snapshot")
      forAll(coins) { createdEvent =>
        Amulet
          .jsonDecoder()
          .decode(new JsonLfReader(createdEvent.createArguments.noSpaces))
          .owner should be(aliceUserParty.toProtoPrimitive)
      }

      val holdingsState = sv1ScanBackend.getHoldingsStateAt(
        CantonTimestamp.assertFromInstant(snapshotAfter.value.toInstant),
        migrationId,
        partyIds = Vector(aliceUserParty),
      )
      inside(holdingsState) { case Some(holdings) =>
        holdings.createdEvents should be(coins)
      }
    }
  }
}
