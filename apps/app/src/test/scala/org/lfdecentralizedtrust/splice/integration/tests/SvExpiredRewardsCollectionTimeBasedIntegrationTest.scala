package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  ValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.util.{Contract, SvTestUtil}

import java.util.UUID
import scala.concurrent.duration.*

class SvExpiredRewardsCollectionTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithSharedEnvironment
    with SvTestUtil {

  "collect expired reward coupons" in { implicit env =>
    def getRewardCoupons(
        round: Contract[OpenMiningRound.ContractId, OpenMiningRound]
    ) = {
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(AppRewardCoupon.COMPANION)(
          dsoParty,
          co => co.data.round.number == round.payload.round.number,
        ) ++
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorRewardCoupon.COMPANION)(
            dsoParty,
            co => co.data.round.number == round.payload.round.number,
          )
    }

    startAllSync(aliceValidatorBackend, bobValidatorBackend)

    val round =
      sv1ScanBackend.getTransferContextWithInstances(getLedgerTime).latestOpenMiningRound.contract
    // There may be rewards left over from other tests, so we first check the
    // contract IDs of existing ones, and compare to that below
    val leftoverRewardIds = getRewardCoupons(round).view.map(_.id).toSet

    // Tap to pay preapproval fees
    aliceValidatorWalletClient.tap(100.0)
    bobValidatorWalletClient.tap(100.0)
    // Self feature to get app rewards
    aliceValidatorWalletClient.selfGrantFeaturedAppRight()
    bobValidatorWalletClient.selfGrantFeaturedAppRight()

    val (aliceParty, bobParty) = onboardAliceAndBob()
    aliceWalletClient.createTransferPreapproval()
    bobWalletClient.createTransferPreapproval()
    aliceWalletClient.tap(100.0)
    bobWalletClient.tap(100.0)

    actAndCheck()(
      "Generate some reward coupons by executing a few direct transfers", {
        aliceWalletClient.transferPreapprovalSend(bobParty, 10.0, UUID.randomUUID.toString)
        aliceWalletClient.transferPreapprovalSend(bobParty, 10.0, UUID.randomUUID.toString)
        bobWalletClient.transferPreapprovalSend(aliceParty, 10.0, UUID.randomUUID.toString)
        bobWalletClient.transferPreapprovalSend(aliceParty, 10.0, UUID.randomUUID.toString)
      },
    )(
      "Wait for all reward coupons to be created",
      _ => {
        // advance rounds for the reward triggers to run
        advanceRoundsToNextRoundOpening
        getRewardCoupons(round)
          .filterNot(c =>
            leftoverRewardIds(c.id)
          ) should have length 6 // 4 featured app rewards + 2 validator from setting up preapprovals
      },
    )
    actAndCheck(
      timeUntilSuccess = 30.seconds
    )(
      "Advance 5 ticks, to close the round",
      (1 to 5).foreach(_ => {
        eventually() {
          ensureSvRewardCouponReceivedForCurrentRound(sv1ScanBackend, sv1WalletClient)
        }
        advanceRoundsToNextRoundOpening
      }),
    )(
      "Wait for all unclaimed coupons to be archived and the closed round to be archived",
      _ => {
        getRewardCoupons(round) shouldBe empty
        sv1ScanBackend
          .getClosedRounds()
          .filter(r => r.payload.round.number == round.payload.round.number) should be(empty)
        val (lastRound, _) = sv1ScanBackend.getRoundOfLatestData()
        sv1WalletClient
          .listSvRewardCoupons()
          .filter(_.payload.round.number <= lastRound) should be(
          empty
        )
      },
    )

    // it seems that without this, the round-party-totals aggregations cannot be computed for SV-2,
    // and the scan-txlog script fails because it expects those to be there.
    advanceRoundsToNextRoundOpening
  }
}
