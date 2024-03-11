package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coin.{AppRewardCoupon, ValidatorRewardCoupon}
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.config.CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import com.daml.network.util.Contract

import scala.concurrent.duration.*

class SvExpiredRewardsCollectionTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithSharedEnvironment {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    // The coupons received by this trigger are never collected due to `withoutAutomaticRewardsCollectionAndCoinMerging`
    // in the parent definition. We disable it so that the round can be archived and the other rewards expired.
    // TODO (#10424): this should no longer be necessary once the SvRewardCoupons are expired.
    super.environmentDefinition.addConfigTransforms((_, conf) =>
      updateAutomationConfig(ConfigurableApp.Sv)(
        _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
      )(conf)
    )
  }

  "collect expired reward coupons" in { implicit env =>
    def getRewardCoupons(
        round: Contract[OpenMiningRound.ContractId, OpenMiningRound]
    ) = {
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(AppRewardCoupon.COMPANION)(
          svcParty,
          co => co.data.round.number == round.payload.round.number,
        ) ++
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorRewardCoupon.COMPANION)(
            svcParty,
            co => co.data.round.number == round.payload.round.number,
          )
    }

    startAllSync(aliceValidatorBackend, bobValidatorBackend)

    val round =
      sv1ScanBackend.getTransferContextWithInstances(getLedgerTime).latestOpenMiningRound.contract
    // There may be rewards left over from other tests, so we first check the
    // contract IDs of existing ones, and compare to that below
    val leftoverRewardIds = getRewardCoupons(round).view.map(_.id).toSet

    val (aliceParty, bobParty) = onboardAliceAndBob()
    aliceWalletClient.tap(100.0)
    bobWalletClient.tap(100.0)

    actAndCheck(
      "Generate some reward coupons by executing a few direct transfers", {
        p2pTransfer(aliceWalletClient, bobWalletClient, bobParty, 10.0)
        p2pTransfer(aliceWalletClient, bobWalletClient, bobParty, 10.0)
        p2pTransfer(bobWalletClient, aliceWalletClient, aliceParty, 10.0)
        p2pTransfer(bobWalletClient, aliceWalletClient, aliceParty, 10.0)
      },
    )(
      "Wait for all reward coupons to be created",
      _ => {
        getRewardCoupons(round)
          .filterNot(c =>
            leftoverRewardIds(c.id)
          ) should have length 8 // 4 app rewards + 4 validator
      },
    )

    actAndCheck(
      timeUntilSuccess = 30.seconds
    )(
      "Advance 5 ticks, to close the round",
      (1 to 5).foreach(_ => advanceRoundsByOneTick),
    )(
      "Wait for all unclaimed coupons to be archived and the closed round to be archived",
      _ => {
        getRewardCoupons(round) shouldBe empty
        sv1ScanBackend
          .getClosedRounds()
          .filter(r => r.payload.round.number == round.payload.round.number) should be(empty)
      },
    )
  }
}
