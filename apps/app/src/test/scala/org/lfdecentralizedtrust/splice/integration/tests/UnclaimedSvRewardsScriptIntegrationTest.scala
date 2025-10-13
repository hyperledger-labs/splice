package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.config.NonNegativeFiniteDuration

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.sys.process.ProcessLogger
import scala.util.control.NonFatal

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.ArchiveClosedMiningRoundsTrigger
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpireRewardCouponsTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.util.*

class UnclaimedSvRewardsScriptIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with HasActorSystem
    with HasExecutionContext
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[ArchiveClosedMiningRoundsTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      .withTrafficTopupsDisabled

  "unclaimed_sv_rewards.py summarizes claimed, expired, and unclaimed minting rewards for a given beneficiary" in {
    implicit env =>
      def expireIssuingMiningRoundTrigger = sv1Backend.dsoDelegateBasedAutomation
        .trigger[ExpireIssuingMiningRoundTrigger]
      def expireRewardCouponsTrigger = sv1Backend.dsoDelegateBasedAutomation
        .trigger[ExpireRewardCouponsTrigger]
      def receiveSvRewardCouponTrigger = sv1Backend.dsoAutomation
        .trigger[ReceiveSvRewardCouponTrigger]
      def sv1CollectRewardsAndMergeAmuletsTrigger =
        sv1ValidatorBackend
          .userWalletAutomation(sv1WalletClient.config.ledgerApiUser)
          .futureValue
          .trigger[CollectRewardsAndMergeAmuletsTrigger]

      val svRewardCouponsCount = 6L
      val svRewardCouponsExpiredCount = 3L
      val svRewardCouponsClaimedCount = 3L
      val svRewardCouponsUnclaimedCount = 0L

      val sv1Name = sv1Backend.config.onboarding.map(_.name).getOrElse(fail("sv1 name not found"))
      val sv1TotalWeight: Long =
        sv1Backend.config.rewardWeightBpsOf(sv1Name).getOrElse(fail("sv1 weight not found"))
      sv1TotalWeight should be > 0L

      // Some rewards gets created before now
      val beginRecordTime = Instant.now().minus(10, ChronoUnit.MINUTES)

      clue("Pause triggers") {
        sv1CollectRewardsAndMergeAmuletsTrigger.pause().futureValue
        expireRewardCouponsTrigger.pause().futureValue
      }

      clue("SV reward coupons for round 0,1,2 have been created") {
        sv1WalletClient.listSvRewardCoupons() should have size 3
      }

      val (_, svRewardCoupons) = actAndCheck(
        "Advance to round 5",
        Range(0, 3).foreach(_ => advanceRoundsByOneTickViaAutomation()),
      )(
        "All reward coupons got created",
        _ => {
          val svRewardCoupons = sv1WalletClient.listSvRewardCoupons()
          svRewardCoupons should have size svRewardCouponsCount
          svRewardCoupons
        },
      )
      // The script will consider all reward coupons created
      val endRecordTime = Instant.now()

      receiveSvRewardCouponTrigger.pause().futureValue

      // Expire
      ///////////

      actAndCheck(
        "Resume expired trigger", {
          expireRewardCouponsTrigger.resume()
        },
      )(
        "Coupons for round 0,1,2 get expired",
        _ => {
          sv1WalletClient
            .listSvRewardCoupons() should have size (svRewardCouponsCount - svRewardCouponsExpiredCount)
          // Pause trigger once we have some coupons expired
          expireRewardCouponsTrigger.pause().futureValue
        },
      )
      val svRewardCouponsAfterExpiry = sv1WalletClient.listSvRewardCoupons()
      val svRewardCouponsExpired = svRewardCoupons.diff(svRewardCouponsAfterExpiry)

      // Claim
      //////////

      setTriggersWithin(
        triggersToPauseAtStart = Seq(expireIssuingMiningRoundTrigger),
        triggersToResumeAtStart = Seq(sv1CollectRewardsAndMergeAmuletsTrigger),
      ) {
        actAndCheck(
          "Advance rounds to allow claiming the remaining SV reward coupons",
          Range(0, 3).foreach(_ => advanceRoundsByOneTickViaAutomation()),
        )(
          "Coupons for rounds 3, 4 and 5 get claimed",
          _ =>
            sv1WalletClient
              .listSvRewardCoupons() should have size
              (svRewardCouponsCount - svRewardCouponsExpiredCount - svRewardCouponsClaimedCount),
        )
      }
      val svRewardCouponsAfterClaiming = sv1WalletClient.listSvRewardCoupons()
      val svRewardCouponsClaimed = svRewardCouponsAfterExpiry.diff(svRewardCouponsAfterClaiming)

      // Run script and check results
      //////////////////////////////////

      clue(
        "Advance so all rounds up to 5 are closed"
      ) {
        // Note: ArchiveClosedMiningRoundsTrigger is paused for this test
        Range(0, 2).foreach(_ => advanceRoundsByOneTickViaAutomation())
      }

      // Calculate totals
      val roundInfo = Map.from(
        sv1ScanBackend
          .getClosedRounds()
          .map(co =>
            (co.payload.round.number.longValue(), BigDecimal(co.payload.issuancePerSvRewardCoupon))
          )
      )

      val sv1Party = sv1Backend.getDsoInfo().svParty
      val readLines = mutable.Buffer[String]()
      clue(
        "Run unclaimed_sv_rewards.py with invalid weight inputs (effective weight < input weight) " +
          "and check warnings and results"
      ) {
        val errorProcessor = ProcessLogger(line => readLines.append(line))
        val inputWeight = sv1TotalWeight - 1
        val alreadyMintedWeight = 2
        val effectiveWeight = sv1TotalWeight - alreadyMintedWeight

        val rewardExpiredTotalAmount =
          getTotalAmount(svRewardCouponsExpired, roundInfo, effectiveWeight)
        val rewardClaimedTotalAmount =
          getTotalAmount(svRewardCouponsClaimed, roundInfo, effectiveWeight)

        try {
          val exitCode = scala.sys.process
            .Process(
              Seq(
                "python",
                "scripts/scan-txlog/unclaimed_sv_rewards.py",
                sv1ScanBackend.httpClientConfig.url.toString(),
                "--grace-period-for-mining-rounds-in-minutes",
                "30",
                "--loglevel",
                "DEBUG",
                "--beneficiary",
                sv1Party.toProtoPrimitive,
                "--begin-migration-id",
                "0",
                "--begin-record-time",
                beginRecordTime.toString,
                "--end-record-time",
                endRecordTime.toString,
                "--weight",
                inputWeight.toString,
                "--already-minted-weight",
                alreadyMintedWeight.toString,
              )
            )
            .!(errorProcessor)

          assert(exitCode == 0, s"Script exited with code $exitCode")
          readLines.filter(_.startsWith("ERROR:")) shouldBe empty
          forExactly(6, readLines) {
            _ should include("WARNING:global:Invalid weight input for round")
          }

          forExactly(1, readLines) {
            _ should include(s"reward_expired_count = $svRewardCouponsExpiredCount")
          }
          forExactly(1, readLines) {
            _ should include(f"reward_expired_total_amount = $rewardExpiredTotalAmount%.10f")
          }
          forExactly(1, readLines) {
            _ should include(s"reward_claimed_count = $svRewardCouponsClaimedCount")
          }
          forExactly(1, readLines) {
            _ should include(f"reward_claimed_total_amount = $rewardClaimedTotalAmount%.10f")
          }
          forExactly(1, readLines) {
            _ should include(s"reward_unclaimed_count = $svRewardCouponsUnclaimedCount")
          }
        } catch {
          case NonFatal(ex) =>
            readLines.foreach(logger.error(_))
            fail("Unexpected failure running script", ex)
        }
      }

      clue(
        "Run unclaimed_sv_rewards.py with invalid weight inputs (effective weight == 0) " +
          "and check warnings and results"
      ) {
        val inputWeight = 1
        val alreadyMintedWeight = sv1TotalWeight + 1
        val effectiveWeight = 0L // max(0, sv1TotalWeight - alreadyMintedWeight)

        val rewardExpiredTotalAmount =
          getTotalAmount(svRewardCouponsExpired, roundInfo, effectiveWeight)
        val rewardClaimedTotalAmount =
          getTotalAmount(svRewardCouponsClaimed, roundInfo, effectiveWeight)

        readLines.clear()
        val errorProcessor = ProcessLogger(line => readLines.append(line))
        try {
          val exitCode = scala.sys.process
            .Process(
              Seq(
                "python",
                "scripts/scan-txlog/unclaimed_sv_rewards.py",
                sv1ScanBackend.httpClientConfig.url.toString(),
                "--grace-period-for-mining-rounds-in-minutes",
                "30",
                "--loglevel",
                "DEBUG",
                "--beneficiary",
                sv1Party.toProtoPrimitive,
                "--begin-migration-id",
                "0",
                "--begin-record-time",
                beginRecordTime.toString,
                "--end-record-time",
                endRecordTime.toString,
                "--weight",
                inputWeight.toString,
                "--already-minted-weight",
                alreadyMintedWeight.toString,
              )
            )
            .!(errorProcessor)

          assert(exitCode == 0, s"Script exited with code $exitCode")
          readLines.filter(_.startsWith("ERROR:")) shouldBe empty
          forExactly(6, readLines) {
            _ should include("WARNING:global:Invalid weight input for round")
          }

          forExactly(1, readLines) {
            _ should include(s"reward_expired_count = $svRewardCouponsExpiredCount")
          }
          forExactly(1, readLines) {
            _ should include(f"reward_expired_total_amount = $rewardExpiredTotalAmount%.10f")
          }
          forExactly(1, readLines) {
            _ should include(s"reward_claimed_count = $svRewardCouponsClaimedCount")
          }
          forExactly(1, readLines) {
            _ should include(f"reward_claimed_total_amount = $rewardClaimedTotalAmount%.10f")
          }
          forExactly(1, readLines) {
            _ should include(s"reward_unclaimed_count = $svRewardCouponsUnclaimedCount")
          }
        } catch {
          case NonFatal(ex) =>
            readLines.foreach(logger.error(_))
            fail("Unexpected failure running script", ex)
        }
      }

      clue(
        "Run unclaimed_sv_rewards.py with valid inputs (effective weight == input weight) and check results"
      ) {
        val inputWeight = sv1TotalWeight / 2
        val alreadyMintedWeight = inputWeight
        val effectiveWeight = inputWeight

        val rewardExpiredTotalAmount =
          getTotalAmount(svRewardCouponsExpired, roundInfo, effectiveWeight)
        val rewardClaimedTotalAmount =
          getTotalAmount(svRewardCouponsClaimed, roundInfo, effectiveWeight)

        readLines.clear()
        val errorProcessor = ProcessLogger(line => readLines.append(line))
        try {
          val exitCode = scala.sys.process
            .Process(
              Seq(
                "python",
                "scripts/scan-txlog/unclaimed_sv_rewards.py",
                sv1ScanBackend.httpClientConfig.url.toString(),
                "--grace-period-for-mining-rounds-in-minutes",
                "30",
                "--loglevel",
                "DEBUG",
                "--beneficiary",
                sv1Party.toProtoPrimitive,
                "--begin-migration-id",
                "0",
                "--begin-record-time",
                beginRecordTime.toString,
                "--end-record-time",
                endRecordTime.toString,
                "--weight",
                inputWeight.toString,
                "--already-minted-weight",
                alreadyMintedWeight.toString,
              )
            )
            .!(errorProcessor)

          assert(exitCode == 0, s"Script exited with code $exitCode")
          readLines.filter(_.startsWith("ERROR:")) shouldBe empty
          forExactly(1, readLines) {
            _ should include(s"reward_expired_count = $svRewardCouponsExpiredCount")
          }
          forExactly(1, readLines) {
            _ should include(f"reward_expired_total_amount = $rewardExpiredTotalAmount%.10f")
          }
          forExactly(1, readLines) {
            _ should include(s"reward_claimed_count = $svRewardCouponsClaimedCount")
          }
          forExactly(1, readLines) {
            _ should include(f"reward_claimed_total_amount = $rewardClaimedTotalAmount%.10f")
          }
          forExactly(1, readLines) {
            _ should include(s"reward_unclaimed_count = $svRewardCouponsUnclaimedCount")
          }
        } catch {
          case NonFatal(ex) =>
            readLines.foreach(logger.error(_))
            throw new RuntimeException("Unexpected failure running script", ex)
        }
      }
  }

  private def getTotalAmount(
      coupons: Seq[
        Contract[
          amuletCodegen.SvRewardCoupon.ContractId,
          amuletCodegen.SvRewardCoupon,
        ]
      ],
      roundInfo: Map[Long, BigDecimal],
      weight: Long,
  ): BigDecimal = {
    coupons.map { co =>
      val issuancePerSvRewardCoupon =
        roundInfo.getOrElse(co.payload.round.number, fail("Round not found"))
      weight should be <= co.payload.weight.longValue()
      BigDecimal(weight) * issuancePerSvRewardCoupon
    }.sum
  }
}
