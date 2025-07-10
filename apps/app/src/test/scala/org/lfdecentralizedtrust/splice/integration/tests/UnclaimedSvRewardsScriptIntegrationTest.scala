package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.config.NonNegativeFiniteDuration

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.sys.process.ProcessLogger
import scala.util.control.NonFatal

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.ArchiveClosedMiningRoundsTrigger
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
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
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      .withTrafficTopupsDisabled

  "unclaimed_sv_rewards.py summarizes claimed, expired, and unclaimed minting rewards for a given beneficiary" in {
    implicit env =>
      def openMiningRounds: Seq[Round] =
        sv1Backend.listOpenMiningRounds().map(_.payload.round)
      def expireRewardCouponsTrigger = sv1Backend.dsoDelegateBasedAutomation
        .trigger[ExpireRewardCouponsTrigger]
      def receiveSvRewardCouponTrigger = sv1Backend.dsoAutomation
        .trigger[ReceiveSvRewardCouponTrigger]
      def sv1CollectRewardsAndMergeAmuletsTrigger =
        sv1ValidatorBackend
          .userWalletAutomation(sv1WalletClient.config.ledgerApiUser)
          .futureValue
          .trigger[CollectRewardsAndMergeAmuletsTrigger]

      // Some rewards gets created before now
      val beginRecordTime = Instant.now().minus(10, ChronoUnit.MINUTES)

      clue("Pause triggers") {
        sv1CollectRewardsAndMergeAmuletsTrigger.pause().futureValue
        expireRewardCouponsTrigger.pause().futureValue
      }

      clue("Advance some rounds") {
        Range(0, 4).foreach(_ => advanceRoundsByOneTickViaAutomation())
        logger.debug(s"openMiningRounds: $openMiningRounds")
      }

      clue("Pause sv rewards generation") {
        receiveSvRewardCouponTrigger.pause().futureValue
      }

      val svRewardCoupons = sv1WalletClient.listSvRewardCoupons()
      val svRewardCouponsCount = svRewardCoupons.length
      svRewardCouponsCount should not be 0
      logger.debug(s"SvRewardCoupons count: $svRewardCouponsCount")

      // Expire
      ///////////

      clue("Resume trigger to expire some coupons") {
        expireRewardCouponsTrigger.resume()
        eventuallySucceeds(timeUntilSuccess = 60.seconds) {
          sv1WalletClient.listSvRewardCoupons().length should be < svRewardCouponsCount
          // Pause trigger once we have some coupons expired
          expireRewardCouponsTrigger.pause().futureValue
        }
      }

      val svRewardCouponsAfterExpiry = sv1WalletClient.listSvRewardCoupons()
      val svRewardCouponsAfterExpiryCount = svRewardCouponsAfterExpiry.length
      val svRewardCouponsExpired = svRewardCoupons.diff(svRewardCouponsAfterExpiry)
      val svRewardCouponsExpiredCount = svRewardCouponsExpired.length
      logger.debug(s"SvRewardCoupons expired count: $svRewardCouponsExpiredCount")

      // Claim
      //////////

      clue("Resume trigger to claim some coupons") {
        sv1CollectRewardsAndMergeAmuletsTrigger.resume()
        eventuallySucceeds(timeUntilSuccess = 60.seconds) {
          sv1WalletClient.listSvRewardCoupons().length should be < svRewardCouponsAfterExpiryCount
          // Pause trigger once we have some coupons claimed
          sv1CollectRewardsAndMergeAmuletsTrigger.pause().futureValue
        }
      }

      val svRewardCouponsAfterClaiming = sv1WalletClient.listSvRewardCoupons()
      val svRewardCouponsClaimed = svRewardCouponsAfterExpiry.diff(svRewardCouponsAfterClaiming)
      val svRewardCouponsClaimedCount = svRewardCouponsClaimed.length
      val svRewardCouponsUnclaimedCount =
        svRewardCouponsCount - svRewardCouponsExpiredCount - svRewardCouponsClaimedCount
      logger.debug(s"SvRewardCoupons claimed count: $svRewardCouponsClaimedCount")
      logger.debug(s"SvRewardCoupons unclaimed count: $svRewardCouponsUnclaimedCount")

      // Run script and check results
      //////////////////////////////////

      clue(
        "Advance some rounds to have the close the rounds where the coupons have been expired or claimed"
      ) {
        // Note: ArchiveClosedMiningRoundsTrigger is paused for this test
        Range(0, 4).foreach(_ => advanceRoundsByOneTickViaAutomation())
      }

      // Calculate totals
      val roundInfo = Map.from(
        sv1ScanBackend
          .getClosedRounds()
          .map(co =>
            (co.payload.round.number.longValue(), BigDecimal(co.payload.issuancePerSvRewardCoupon))
          )
      )
      val rewardExpiredTotalAmount = getTotalAmount(svRewardCouponsExpired, roundInfo)
      val rewardClaimedTotalAmount = getTotalAmount(svRewardCouponsClaimed, roundInfo)
      logger.debug(s"rewardExpiredTotalAmount: $rewardExpiredTotalAmount")
      logger.debug(s"rewardClaimedTotalAmount: $rewardClaimedTotalAmount")

      // Add some minutes in case discrepancies with ledger time
      val endRecordTime = Instant
        .now()
        .plus(5, ChronoUnit.MINUTES)

      clue("Run unclaimed_sv_rewards.py and check results") {
        val sv1Party = sv1Backend.getDsoInfo().svParty
        val readLines = mutable.Buffer[String]()
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
              )
            )
            .!(errorProcessor)

          assert(exitCode == 0, s"Script exited with code $exitCode")
          readLines.filter(_.startsWith("ERROR:")) shouldBe empty
          withClue(s"Expected: reward_expired_count = $svRewardCouponsExpiredCount") {
            readLines.exists(
              _.contains(s"reward_expired_count = $svRewardCouponsExpiredCount")
            ) shouldBe true
          }
          withClue(s"Expected: reward_expired_total_amount =") {
            readLines.exists(
              _.contains(s"reward_expired_total_amount = $rewardExpiredTotalAmount")
            ) shouldBe true
          }
          withClue(s"Expected: reward_claimed_count = $svRewardCouponsClaimedCount") {
            readLines.exists(
              _.contains(s"reward_claimed_count = $svRewardCouponsClaimedCount")
            ) shouldBe true
          }
          withClue(s"Expected: reward_claimed_total_amount = $rewardClaimedTotalAmount") {
            readLines.exists(_.contains("reward_claimed_total_amount =")) shouldBe true
          }
          withClue(s"Expected: reward_unclaimed_count = $svRewardCouponsUnclaimedCount") {
            readLines.exists(
              _.contains(s"reward_unclaimed_count = $svRewardCouponsUnclaimedCount")
            ) shouldBe true
          }
        } catch {
          case NonFatal(ex) =>
            readLines.foreach(logger.error(_))
            throw new RuntimeException("Failed to run unclaimed_sv_rewards.py", ex)
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
  ): BigDecimal = {
    coupons.map { co =>
      val issuancePerSvRewardCoupon =
        roundInfo.getOrElse(co.payload.round.number, fail("Round not found"))
      BigDecimal(co.payload.weight.longValue()) * issuancePerSvRewardCoupon
    }.sum
  }
}
