package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsEntry
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.automation.ScanAggregationTrigger
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultAnsConfig
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

class ScanTimeBasedIntegrationTest
    extends IntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
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

  "report correct reference data" in { implicit env =>
    def roundNum() =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number
    roundNum() shouldBe 1

    advanceRoundsByOneTick
    roundNum() shouldBe 2
  }

  "return correct amulet configs" in { implicit env =>
    // TODO(#2930) test also with changing amulet prices.
    // TODO(#2930) Currently we are not guaranteed that the first three rounds are correctly
    // captured in the tx log, so for now we first advance a round, and query only on that
    // round and beyond. Once that is fixed, we should make sure that querying for round 0 is reliable as well.

    advanceRoundsByOneTick

    clue("Get config for round 3") {
      val cfg = eventuallySucceeds() {
        sv1ScanBackend.getAmuletConfigForRound(3)
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

    clue("Try to get config for round 4 which does not yet exist") {
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.getAmuletConfigForRound(4),
        _.errorMessage should include("Round 4 not found"),
      )
    }

    val newHoldingFee = 0.1
    clue("schedule a config change, and advance time for it to take effect") {
      val currentConfigSchedule = sv1ScanBackend.getAmuletRules().contract.payload.configSchedule
      val configSchedule =
        createConfigSchedule(
          currentConfigSchedule,
          (
            defaultTickDuration.asJava,
            mkUpdatedAmuletConfig(
              currentConfigSchedule,
              tickDuration = defaultTickDuration,
              holdingFee = newHoldingFee,
            ),
          ),
        )

      setFutureConfigSchedule(configSchedule)

      advanceRoundsByOneTick
    }
    clue("Round 4 should now be open, and have the new configuration") {
      eventuallySucceeds() {
        sv1ScanBackend.getAmuletConfigForRound(4).holdingFee should be(
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
      advanceRoundsByOneTick
      grantFeaturedAppRight(aliceValidatorWalletClient)
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 41.0)
      p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, 101.0)
    })
    clue("Advance 2 ticks for the first coupons to be collectable")({
      advanceRoundsByOneTick
      advanceRoundsByOneTick
    })
    clue("Alice's and Bob's validators use their app&validator rewards when transfering CC")({
      p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0)
      p2pTransfer(bobValidatorWalletClient, aliceWalletClient, aliceUserParty, 10.0)
    })
    clue("Some more transfers collect more rewards in round 5 (issued in round 1)")({
      advanceRoundsByOneTick
      p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0)
      p2pTransfer(bobValidatorWalletClient, aliceWalletClient, aliceUserParty, 10.0)
    })
    val baseRoundWithLatestData = clue(
      "Advance 1 more tick to make sure we capture at least one round change in the tx history"
    ) {
      advanceRoundsByOneTick
      sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
      sv1ScanBackend.getRoundOfLatestData()._1
    }
    clue("Advance one more tick to get to the next closed round") {
      advanceRoundsByOneTick
      val ledgerTime = getLedgerTime.toInstant
      val expectedLastRound = baseRoundWithLatestData + 1
      sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
      sv1ScanBackend.getRoundOfLatestData() shouldBe (expectedLastRound, ledgerTime)
      sv1ScanBackend.getAggregatedRounds().value shouldBe ScanAggregator.RoundRange(
        0,
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

    def compareLeaderboard(
        result: Seq[(PartyId, BigDecimal)],
        expected: Seq[(WalletAppClientReference, BigDecimal)],
    ) = {
      result shouldBe expected.map((v) =>
        (Codec.decode(Codec.Party)((v._1.userStatus().party)).value, v._2)
      )
    }
    def walletClientParty(walletClient: WalletAppClientReference) =
      Codec.decode(Codec.Party)(walletClient.userStatus().party).value

    actAndCheck(
      "Advance four more rounds, for the previous rounds to close (where rewards were collected)",
      Range(0, 4).foreach(_ => advanceRoundsByOneTick),
    )(
      "Test leaderboards for ends of rounds 4 and 5",
      _ => {
        val ledgerTime = getLedgerTime.toInstant
        sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
        sv1ScanBackend.getRoundOfLatestData() should be((baseRoundWithLatestData + 5, ledgerTime))

        // TODO(#10941): consider de-hard-coding the expected values here somehow, e.g. by only checking them relative to each other
        val appRewardsBobR3 = BigDecimal(4.2000000000)
        val appRewardsAliceR3 = BigDecimal(3.8400000000)
        val validatorRewardsBobR3 = BigDecimal(1.4000000000)
        val validatorRewardsAliceR3 = BigDecimal(1.2800000000)

        (0 to baseRoundWithLatestData.toInt + 3).map { round =>
          sv1ScanBackend.getRewardsCollectedInRound(round.toLong)
        }.sum shouldBe appRewardsAliceR3 + appRewardsBobR3 + validatorRewardsAliceR3 + validatorRewardsBobR3
        val aliceValidatorWalletClientParty =
          walletClientParty(aliceValidatorWalletClient).toProtoPrimitive
        val bobValidatorWalletClientParty =
          walletClientParty(bobValidatorWalletClient).toProtoPrimitive

        clue("Compare leaderboard getTopProvidersByAppRewards + 3") {
          sv1ScanBackend
            .listRoundPartyTotals(0, baseRoundWithLatestData + 3)
            .map { rpt =>
              rpt.party -> (rpt.closedRound, BigDecimal(rpt.cumulativeAppRewards))
            }
            .filter { case (p, (_, appRewards)) =>
              appRewards > 0 && (p == aliceValidatorWalletClientParty || p == bobValidatorWalletClientParty)
            }
            .toMap should contain theSameElementsAs Map(
            aliceValidatorWalletClientParty -> (baseRoundWithLatestData + 3, appRewardsAliceR3),
            bobValidatorWalletClientParty -> (baseRoundWithLatestData + 3, appRewardsBobR3),
          )

          compareLeaderboard(
            sv1ScanBackend.getTopProvidersByAppRewards(baseRoundWithLatestData + 3, 10),
            Seq(
              (bobValidatorWalletClient, appRewardsBobR3),
              (aliceValidatorWalletClient, appRewardsAliceR3),
            ),
          )
        }
        clue("Compare leaderboard getTopValidatorsByValidatorRewards + 3") {
          sv1ScanBackend
            .listRoundPartyTotals(0, baseRoundWithLatestData + 3)
            .map { rpt =>
              rpt.party -> (rpt.closedRound, BigDecimal(rpt.cumulativeValidatorRewards))
            }
            .filter { case (p, (_, validatorRewards)) =>
              validatorRewards > 0 && (p == aliceValidatorWalletClientParty || p == bobValidatorWalletClientParty)
            }
            .toMap should contain theSameElementsAs Map(
            aliceValidatorWalletClientParty -> (baseRoundWithLatestData + 3, validatorRewardsAliceR3),
            bobValidatorWalletClientParty -> (baseRoundWithLatestData + 3, validatorRewardsBobR3),
          )

          compareLeaderboard(
            sv1ScanBackend.getTopValidatorsByValidatorRewards(baseRoundWithLatestData + 3, 10),
            Seq(
              (bobValidatorWalletClient, validatorRewardsBobR3),
              (aliceValidatorWalletClient, validatorRewardsAliceR3),
            ),
          )
        }
        clue("Compare leaderboard getTopProvidersByAppRewards + 4") {
          compareLeaderboard(
            sv1ScanBackend.getTopProvidersByAppRewards(baseRoundWithLatestData + 4, 10),
            Seq(
              // TODO(#10941): consider de-hard-coding the expected values here
              (bobValidatorWalletClient, BigDecimal(8.4060000000)),
              (aliceValidatorWalletClient, BigDecimal(7.6860000000)),
            ),
          )
        }
        clue("Compare leaderboard getTopValidatorsByValidatorRewards + 4") {
          compareLeaderboard(
            sv1ScanBackend.getTopValidatorsByValidatorRewards(baseRoundWithLatestData + 4, 10),
            Seq(
              // TODO(#10941): consider de-hard-coding the expected values here
              (bobValidatorWalletClient, BigDecimal(2.8020000000)),
              (aliceValidatorWalletClient, BigDecimal(2.5620000000)),
            ),
          )
        }
      },
    )
  }

  "get total amulet balance" in { implicit env =>
    val (_, _) = onboardAliceAndBob()

    def roundNum() =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number
    roundNum() shouldBe 1

    val tapRound1Amount = BigDecimal(500.0)
    clue("Tap in round 1") {
      aliceWalletClient.tap(tapRound1Amount)
    }

    advanceRoundsByOneTick

    roundNum() shouldBe 2

    val tapRound2Amount = BigDecimal(500.0)
    clue("Tap in round 2") {
      bobWalletClient.tap(tapRound2Amount)
    }

    actAndCheck(
      "advance to close round 2",
      (0 to 4).foreach(_ => advanceRoundsByOneTick),
    )(
      "check round 2 is closed",
      _ => {
        sv1ScanBackend.automation.trigger[ScanAggregationTrigger].runOnce().futureValue
        sv1ScanBackend.getRoundOfLatestData()._1 shouldBe 2
        sv1ScanBackend.getAggregatedRounds().value shouldBe ScanAggregator.RoundRange(0, 2)
      },
    )

    clue("Get total balances for round 0, 1 and 2") {
      val total0 = sv1ScanBackend.getTotalAmuletBalance(0)
      val total1 = sv1ScanBackend.getTotalAmuletBalance(1)
      val total2 = sv1ScanBackend.getTotalAmuletBalance(2)

      val holdingFeeAfterOneRound = 1 * defaultHoldingFeeAmulet
      val holdingFeeAfterTwoRounds = 2 * defaultHoldingFeeAmulet

      total0 shouldBe 0.0
      total1 shouldBe (walletUsdToAmulet(tapRound1Amount) - holdingFeeAfterOneRound)
      total2 shouldBe (
        walletUsdToAmulet(tapRound1Amount) - holdingFeeAfterTwoRounds +
          walletUsdToAmulet(tapRound2Amount) - holdingFeeAfterOneRound
      )
      sv1ScanBackend.getAggregatedRounds().value shouldBe ScanAggregator.RoundRange(0, 2)
      sv1ScanBackend
        .listRoundTotals(0, 2)
        .map(rt => (rt.closedRound, BigDecimal(rt.totalAmuletBalance))) shouldBe List(
        0L -> total0,
        1L -> total1,
        2L -> total2,
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundTotals(1, 3),
        _.errorMessage should include("is outside of the available rounds range"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundPartyTotals(1, 3),
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
        sv1ScanBackend.listRoundTotals(10, 9),
        _.errorMessage should include("end_round 9 must be >= start_round 10"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundPartyTotals(10, 9),
        _.errorMessage should include("end_round 9 must be >= start_round 10"),
      )
    }
    clue("Try to get too many round totals or round party totals") {
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundTotals(0, 200),
        _.errorMessage should include(s"Cannot request more than 200 rounds at a time"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundTotals(1, 201),
        _.errorMessage should include(s"Cannot request more than 200 rounds at a time"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundPartyTotals(0, 50),
        _.errorMessage should include(s"Cannot request more than 50 rounds at a time"),
      )
      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.listRoundPartyTotals(1, 51),
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
          PackageQualifiedName(Amulet.TEMPLATE_ID_WITH_PACKAGE_ID),
          PackageQualifiedName(AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID),
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
