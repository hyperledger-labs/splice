package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.LocalSequencerConnectionsTrigger
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, TimeTestUtil, WalletTestUtil}

import scala.util.Try

class ScanWithGradualStartsTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with SvTestUtil {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          // SVs's sequencer connection choice is not relevant to this test
          // but the reconciliation can cause flakiness
          // TODO(#979): Unpause again once this reconciliation is less disruptive
          _.withPausedTrigger[LocalSequencerConnectionsTrigger]
        )(config)
      )
      .withManualStart

  "initialize a scan app that joins late" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1Backend,
      sv1ValidatorBackend,
      aliceValidatorBackend,
      bobValidatorBackend,
    )

    val _ = onboardAliceAndBob()

    clue("Tap some amulet before sv2 scan app starts") {
      aliceWalletClient.tap(20)
      bobWalletClient.tap(3)
    }

    val firstOpenRound = clue("Start sv2 app and scan") {
      sv2Backend.startSync()
      sv2ScanBackend.startSync()
      eventually() {
        val sv2OpenRounds = sv2ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
        val sv1OpenRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1

        sv2OpenRounds should be(sv1OpenRounds)

        val maxOpenRoundFromACS = sv2OpenRounds
          .map(_.contract.payload.round.number)
          .max
        // sv2 scan sees round 3 as first round opening after ACS, will get round 2 aggregates from sv1 scan
        maxOpenRoundFromACS shouldBe 2
        sv2OpenRounds.head
      }
    }

    clue("Tap some more amulet now that sv2 scan is up") {
      aliceWalletClient.tap(3)
    }

    // advance rounds for the reward triggers to run
    advanceTimeForRewardAutomationToRunForCurrentRound

    // TODO(DACH-NY/canton-network-node#2930): Since we are reporting in getRoundOfLatestData() only the latest round that is aggregated (fully closed),
    // we must advance rounds until round 3 closes, which is the first round that sv2's scan is guaranteed to have seen.
    (firstOpenRound.payload.round.number.toInt to (firstOpenRound.payload.round.number.toInt + 6))
      .foreach { n =>
        clue("Ensure SvRewardCoupons are received") {
          eventually() {
            ensureSvRewardCouponReceivedForCurrentRound(sv1ScanBackend, sv1WalletClient)
            // sv2 did not start up it's validator app (thus wallet), so it won't claim any coupons.
          }
        }
        clue("Ensure ValidatorLivenessActivityRecord are received") {
          eventually() {
            Seq(sv1WalletClient, aliceValidatorWalletClient, bobValidatorWalletClient).foreach {
              walletClient =>
                ensureValidatorLivenessActivityRecordReceivedForCurrentRound(
                  sv1ScanBackend,
                  walletClient,
                )
            }
          }
        }

        advanceRoundsToNextRoundOpening

        val roundForWhichCouponsAreNowRedeemed = n.toLong - 2
        if (roundForWhichCouponsAreNowRedeemed >= 0) {
          // you're not guaranteed that a coupon will be claimed in the first round possible if the rounds advance too quickly,
          // so we make sure that it happens so the balances at the end make sense. See flake in issue #10923.
          clue("Ensure SvRewardCoupons are redeemed") {
            eventually() {
              Seq(sv1WalletClient, aliceValidatorWalletClient, bobValidatorWalletClient).foreach {
                walletClient =>
                  ensureNoSvRewardCouponExistsForRound(
                    roundForWhichCouponsAreNowRedeemed,
                    walletClient,
                  )
              }
            }
          }
          clue("Ensure ValidatorLivenessActivityRecords are redeemed") {
            eventually() {
              Seq(sv1WalletClient, aliceValidatorWalletClient, bobValidatorWalletClient).foreach {
                walletClient =>
                  ensureNoValidatorLivenessActivityRecordExistsForRound(
                    roundForWhichCouponsAreNowRedeemed,
                    walletClient,
                  )
              }
            }
          }
        }
      }

    clue("Waiting for scan apps to report rounds as closed") {
      eventually() {
        Try(sv2ScanBackend.getRoundOfLatestData()._1).success.value shouldBe 3
        Try(sv1ScanBackend.getRoundOfLatestData()._1).success.value shouldBe 3
      }
    }

    val validatorLivenessActivityRecordAmount = 2.85

    clue("Aggregated rewards collected on both scan apps should match") {
      forEvery(
        Table(
          ("round", "total floor", "total ceiling"),
          (2L, BigDecimal(0), BigDecimal(0)),
          (
            3L,
            walletUsdToAmulet(validatorLivenessActivityRecordAmount * 3 - smallAmount),
            walletUsdToAmulet(validatorLivenessActivityRecordAmount * 3),
          ),
        )
      ) { (round, floor, ceil) =>
        val rewards1 = sv1ScanBackend.getRewardsCollectedInRound(round)
        val rewards2 = sv2ScanBackend.getRewardsCollectedInRound(round)
        rewards1 shouldBe rewards2
        rewards1 should beWithin(floor, ceil)
      }
    }
  }
}
