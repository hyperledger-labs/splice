package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import org.lfdecentralizedtrust.splice.util.{
  SpliceUtil,
  ConfigScheduleUtil,
  TimeTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.AmuletMetricsTrigger
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.ShowUtil.*
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

import java.time.Instant
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*

@nowarn("msg=match may not be exhaustive")
class TimeBasedTreasuryIntegrationTestWithoutMerging
    extends IntegrationTest
    with ConfigScheduleUtil
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // for testing that input limits are respected.
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .addConfigTransforms(
        (_, config) =>
          // for testing that input limits are respected.
          ConfigTransforms
            .updateAllSvAppFoundDsoConfigs_(_.focus(_.initialMaxNumInputs).replace(4))(config),
        (_, config) =>
          // Pausing since this makes scan requests which then messes with the cache assertions
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Validator)(
            _.withPausedTrigger[AmuletMetricsTrigger]
          )(config),
      )
  }

  "rewards from older rounds are prioritized while respecting maxNumInputs" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()

    aliceValidatorWalletClient.tap(100)
    createRewardsInRound(aliceValidatorWalletClient, aliceWalletClient, alice, 1)
    advanceRoundsByOneTick
    createRewardsInRound(aliceValidatorWalletClient, aliceWalletClient, alice, 2)
    aliceValidatorWalletClient.tap(50)

    // by advancing three rounds, both round 1 and round 2 are in their issuing phase.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    eventually() {
      aliceValidatorWalletClient.list().amulets should have length 2
      aliceValidatorWalletClient
        .listValidatorRewardCoupons() should have length 2
      aliceValidatorWalletClient
        .listAppRewardCoupons() should have length 2
    }

    clue("rewards from round 1 are merged.") {
      // Note that the rewards from round 2 are not merged as transfers allow at most 4 inputs
      // and the rewards from round 1 are prioritized
      p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 5)
      eventually() {
        aliceValidatorWalletClient.list().amulets should have length 1
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
      }
    }

    clue("rewards from round 2 are merged") {
      p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 5)
      eventually() {
        // fails here when p2p-ing above.
        aliceValidatorWalletClient.list().amulets should have length 1
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
      }
    }
  }

  "more valuable rewards are prioritized while respecting maxNumInputs" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()

    aliceValidatorWalletClient.tap(10000)
    // Execute three transfers that generate different amount of rewards.
    p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 5)
    // second and third transfer are a lot larger than the first one, but very close to each other.
    // because in the first part of the issuance curve already, apps (40%) gain a lot more rewards than validators (12%)
    // the app rewards, the app reward from the second transfer is prioritized over the validator reward from the
    // third (larger) transfer.
    p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 2000)
    p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 2010)

    // by advancing three rounds, round 1 is in the issuing phase.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    eventually() {
      aliceValidatorWalletClient.list().amulets should have length 1
      aliceValidatorWalletClient.listValidatorRewardCoupons() should have length 3
      aliceValidatorWalletClient.listAppRewardCoupons() should have length 3
    }
    val Seq(vrew1, vrew2, _) =
      aliceValidatorWalletClient.listValidatorRewardCoupons().sortBy(_.payload.amount)
    val Seq(arew1, _, _) =
      aliceValidatorWalletClient.listAppRewardCoupons().sortBy(_.payload.amount)
    clue("most valuable rewards are merged first.") {
      p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 5)

      eventually() {
        // four inputs: 1 amulet, 3 rewards.
        // only the most valuable validator reward is chosen as input because of the issuance curve.
        aliceValidatorWalletClient.list().amulets should have length 1
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1)
          .toList shouldBe Seq(vrew1, vrew2)
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1)
          .toList shouldBe Seq(arew1)
      }
    }

    clue("rest of the rewards are merged.") {
      // Note that the rewards from round 2 are not merged as transfers allow at most 4 inputs
      // and the rewards from round 1 are prioritized
      p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 5)

      eventually() {
        aliceValidatorWalletClient.list().amulets should have length 1

        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
      }
    }
  }

  "respect scheduled change of maxNumInputs" in { implicit env =>
    // current config: maxNumInputs = 4
    // We then schedule a reduction of maxNumInputs to 3
    val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
    val currentConfigSchedule = sv1ScanBackend.getAmuletRules().contract.payload.configSchedule
    val configSchedule = new splice.schedule.Schedule(
      mkUpdatedAmuletConfig(currentConfigSchedule, defaultTickDuration, 4),
      List(
        new Tuple2(
          now
            // wait 4 "advanceRoundsByOneTick" (tick + 10s) before changing config
            .add(tickDurationWithBuffer multipliedBy 4 minusSeconds 10)
            .toInstant,
          mkUpdatedAmuletConfig(currentConfigSchedule, defaultTickDuration, 3),
        )
      ).asJava,
    )

    setFutureConfigSchedule(configSchedule)

    val (alice, _) = onboardAliceAndBob()

    aliceValidatorWalletClient.tap(100)
    createRewardsInRound(aliceValidatorWalletClient, aliceWalletClient, alice, 1)
    advanceRoundsByOneTick
    createRewardsInRound(aliceValidatorWalletClient, aliceWalletClient, alice, 2)
    advanceRoundsByOneTick
    createRewardsInRound(aliceValidatorWalletClient, aliceWalletClient, alice, 3)

    // by advancing 2 rounds, both round 1 and round 2 are in their issuing phase
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    aliceValidatorWalletClient.tap(5)
    eventually() {
      val currentInstant =
        sv1Backend.participantClientWithAdminToken.ledger_api.time.get().toInstant
      getOpenIssuingRounds(currentInstant).map(_.data.round.number) shouldBe Seq(1, 2)
      aliceValidatorWalletClient.list().amulets should have length 2
      aliceValidatorWalletClient
        .listValidatorRewardCoupons() should have length 3
      aliceValidatorWalletClient
        .listAppRewardCoupons() should have length 3
    }

    clue("rewards from round 1 are merged.") {
      // Note that the rewards from round 2 are not merged as transfers allow at most 4 inputs
      // and the rewards from round 1 are prioritized
      p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 5)
      eventually() {
        aliceValidatorWalletClient.list().amulets should have length 1
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 3) should have length 1
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 3) should have length 1
      }
    }

    advanceRoundsByOneTick

    clue("rewards from round 2 are merged but not round 3") {
      p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 5)
      eventually() {
        val currentInstant =
          sv1Backend.participantClientWithAdminToken.ledger_api.time.get().toInstant
        getOpenIssuingRounds(currentInstant).map(_.data.round.number) shouldBe Seq(2, 3)
        // As the max number of input is reduced to 3
        // only 3 inputs are used: 1 amulet, ValidatorRewardCoupon from round 2 and AppRewardCoupon from round 2
        aliceValidatorWalletClient.list().amulets should have length 1
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
        aliceValidatorWalletClient
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 3) should have length 1
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 3) should have length 1

      }
    }
  }

  "adjust maxNumInput if there is a tap operation in the batch" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()
    aliceValidatorWalletClient.tap(50)
    p2pTransfer(aliceValidatorWalletClient, aliceWalletClient, alice, 5)
    eventually() {
      aliceValidatorWalletClient.list().amulets should have length 1
    }
    aliceValidatorWalletClient.tap(50)

    eventually() {
      aliceValidatorWalletClient.list().amulets should have length 2
      aliceValidatorWalletClient.listValidatorRewardCoupons() should have length 1
      aliceValidatorWalletClient.listAppRewardCoupons() should have length 1
    }

    // advancing three rounds so the rewards are collectable.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {
        aliceValidatorWalletClient.tap(1)
        eventually() {
          aliceValidatorWalletClient.list().amulets should have length 3
          aliceValidatorWalletClient.listValidatorRewardCoupons() should have length 1
          aliceValidatorWalletClient.listAppRewardCoupons() should have length 1
        }
      },
      entries => {
        forAtLeast(
          1,
          entries,
        )(
          // if we run a tap, only 3 of 4 possible inputs are selected because one input slot is "taken" by the tap
          // (notice how the app reward coupon is not an input)
          _.message should include regex (
            "with inputs Vector\\(InputAmulet\\(.*\\), InputAmulet\\(.*\\), InputAppRewardCoupon\\(.*\\)\\)"
          )
        )
      },
    )

  }

  "ignore expired-amulets in the treasury service input" in { implicit env =>
    val (_, bob) = onboardAliceAndBob()

    aliceWalletClient.tap(100)

    // creating 5 soon-to-be-expired amulets because the 'expire amulet' automation expires
    // 4 amulets at once by default & so even in the case it starts expiring amulets, we have one unexpired amulet for the test.
    // If this test flakes because the automation already expired all expired amulets, increase the number of
    // soon-to-be-expired amulets we create here
    (1 to 5).map(_ => aliceWalletClient.tap(SpliceUtil.defaultHoldingFee.rate))

    eventually() {
      aliceWalletClient.list().amulets should have length 6
    }

    // after one more tick, the amulets have no value and should be ignored.
    advanceRoundsByOneTick

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {
        p2pTransfer(aliceWalletClient, bobWalletClient, bob, 1)
        eventually() {
          bobWalletClient.balance().unlockedQty should be > BigDecimal(0)
          // there is still >1 amulet
          aliceWalletClient.list().amulets.size should be > 1
        }
      },
      entries => {
        forAtLeast(
          1,
          entries,
        )(
          // but only the non-expired amulet is selected as input.
          _.message should include regex (
            "with inputs Vector\\(InputAmulet\\(.*\\)\\)"
          )
        )
      },
    )
  }

  "scan-connection caching avoids unnecessary network calls and re-sending contracts if they are already known by the client" in {
    implicit env =>
      val (_, _) = onboardAliceAndBob()

      clue("create issuing rounds 0 and 1") {
        advanceRoundsByOneTick
        advanceRoundsByOneTick
      }
      // run a tx so alice wallet's cache is hydrated up to issuing round 1.
      aliceWalletClient.tap(5)

      clue("check that the cache is used when tapping twice in a row.") {
        loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          {
            aliceWalletClient.tap(5)
          },
          entries => {
            forAtLeast(
              1,
              entries,
            )(
              _.message should include regex (
                s"Using the client-cache"
              )
            )
          },
        )

      }

      val Seq(_, issuingRound1) = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2

      clue("create issuing round 2") {
        advanceRoundsByOneTick
      }

      clue("check that issuing round 1 is cached") {

        loggerFactory.assertLogsSeq(
          SuppressionRule.LevelAndAbove(Level.DEBUG) && (SuppressionRule.LoggerNameContains(
            "HttpScanHandler"
          ) || SuppressionRule.LoggerNameContains("ScanConnection"))
        )(
          {
            aliceWalletClient.tap(5)
          },
          entries => {
            forAtLeast(
              1,
              entries,
            )(
              _.message should include(
                show"Not sending ${PrettyContractId(issuingRound1.contract)}"
              )
            )
            forAtLeast(
              1,
              entries,
            )(
              _.message should include regex (
                s"querying the scan app for the latest round information"
              )
            )
          },
        )

      }
  }

  private def getOpenIssuingRounds(now: Instant)(implicit env: SpliceTestConsoleEnvironment) = {
    val issuingRounds = getSortedIssuingRounds(sv1Backend.participantClientWithAdminToken, dsoParty)
    issuingRounds.filter(r => now.isAfter(r.data.opensAt))
  }

  private def createRewardsInRound(
      validatorWallet: WalletAppClientReference,
      userWallet: WalletAppClientReference,
      receiverParty: PartyId,
      round: Int,
  ) = {
    p2pTransfer(validatorWallet, userWallet, receiverParty, 5)

    eventually() {
      validatorWallet.list().amulets should have length 1
      validatorWallet
        .listValidatorRewardCoupons()
        .filter(_.payload.round.number == round) should have length 1
      validatorWallet
        .listAppRewardCoupons()
        .filter(_.payload.round.number == round) should have length 1
    }
  }
}
