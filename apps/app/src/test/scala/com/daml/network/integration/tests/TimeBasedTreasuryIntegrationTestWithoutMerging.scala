package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.{ValidatorAppBackendReference, WalletAppClientReference}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.PrettyInstances.*
import com.daml.network.util.{CNNodeUtil, ConfigScheduleUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.ShowUtil.*
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*
import scala.annotation.nowarn

@nowarn("msg=match may not be exhaustive")
class TimeBasedTreasuryIntegrationTestWithoutMerging
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      // for testing that input limits are respected.
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .addConfigTransform((_, config) =>
        // for testing that input limits are respected.
        CNNodeConfigTransforms
          .updateAllSvAppFoundCollectiveConfigs_(_.focus(_.initialMaxNumInputs).replace(4))(config)
      )
  }

  "rewards from older rounds are prioritized while respecting maxNumInputs" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()

    aliceValidatorWallet.tap(100)
    createRewardsInRound(aliceValidator, aliceValidatorWallet, aliceWallet, alice, 1)
    advanceRoundsByOneTick
    createRewardsInRound(aliceValidator, aliceValidatorWallet, aliceWallet, alice, 2)
    aliceValidatorWallet.tap(50)

    // by advancing three rounds, both round 1 and round 2 are in their issuing phase.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    eventually() {
      aliceValidatorWallet.list().coins should have length 2
      aliceValidatorWallet
        .listValidatorRewardCoupons() should have length 2
      aliceValidatorWallet
        .listAppRewardCoupons() should have length 2
    }

    clue("rewards from round 1 are merged.") {
      // Note that the rewards from round 2 are not merged as transfers allow at most 4 inputs
      // and the rewards from round 1 are prioritized
      p2pTransfer(
        aliceValidator,
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )
      eventually() {
        aliceValidatorWallet.list().coins should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
      }
    }

    clue("rewards from round 2 are merged") {
      p2pTransfer(
        aliceValidator,
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )
      eventually() {
        // fails here when p2p-ing above.
        aliceValidatorWallet.list().coins should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
      }
    }
  }

  "more valuable rewards are prioritized while respecting maxNumInputs" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()

    aliceValidatorWallet.tap(10000)
    // Execute three transfers that generate different amount of rewards.
    p2pTransfer(
      aliceValidator,
      aliceValidatorWallet,
      aliceWallet,
      alice,
      5,
    )
    // second and third transfer are a lot larger than the first one, but very close to each other.
    // because in the first part of the issuance curve already, apps (40%) gain a lot more rewards than validators (12%)
    // the app rewards, the app reward from the second transfer is prioritized over the validator reward from the
    // third (larger) transfer.
    p2pTransfer(
      aliceValidator,
      aliceValidatorWallet,
      aliceWallet,
      alice,
      2000,
    )
    p2pTransfer(
      aliceValidator,
      aliceValidatorWallet,
      aliceWallet,
      alice,
      2010,
    )

    // by advancing three rounds, round 1 is in the issuing phase.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    eventually() {
      aliceValidatorWallet.list().coins should have length 1
      aliceValidatorWallet.listValidatorRewardCoupons() should have length 3
      aliceValidatorWallet.listAppRewardCoupons() should have length 3
    }
    val Seq(vrew1, vrew2, _) =
      aliceValidatorWallet.listValidatorRewardCoupons().sortBy(_.payload.amount)
    val Seq(arew1, _, _) =
      aliceValidatorWallet.listAppRewardCoupons().sortBy(_.payload.amount)
    clue("most valuable rewards are merged first.") {
      p2pTransfer(
        aliceValidator,
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )

      eventually() {
        // four inputs: 1 coin, 3 rewards.
        // only the most valuable validator reward is chosen as input because of the issuance curve.
        aliceValidatorWallet.list().coins should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1)
          .toList shouldBe Seq(vrew1, vrew2)
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1)
          .toList shouldBe Seq(arew1)
      }
    }

    clue("rest of the rewards are merged.") {
      // Note that the rewards from round 2 are not merged as transfers allow at most 4 inputs
      // and the rewards from round 1 are prioritized
      p2pTransfer(
        aliceValidator,
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )

      eventually() {
        aliceValidatorWallet.list().coins should have length 1

        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
      }
    }
  }

  "respect scheduled change of maxNumInputs" in { implicit env =>
    // current config: maxNumInputs = 4
    // We then schedule a reduction of maxNumInputs to 3
    val now = svc.participantClientWithAdminToken.ledger_api.time.get()
    val currentConfigSchedule = sv1Scan.getCoinRules().contract.payload.configSchedule
    val configSchedule = new cc.schedule.Schedule(
      mkUpdatedCoinConfig(currentConfigSchedule, defaultTickDuration, 4),
      List(
        new Tuple2(
          now.add(Duration.ofSeconds(160 * 4 - 10)).toInstant,
          mkUpdatedCoinConfig(currentConfigSchedule, defaultTickDuration, 3),
        )
      ).asJava,
    )
    setConfigSchedule(configSchedule)

    val (alice, _) = onboardAliceAndBob()

    aliceValidatorWallet.tap(100)
    createRewardsInRound(aliceValidator, aliceValidatorWallet, aliceWallet, alice, 1)
    advanceRoundsByOneTick
    createRewardsInRound(aliceValidator, aliceValidatorWallet, aliceWallet, alice, 2)
    advanceRoundsByOneTick
    createRewardsInRound(aliceValidator, aliceValidatorWallet, aliceWallet, alice, 3)

    // by advancing 2 rounds, both round 1 and round 2 are in their issuing phase
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    aliceValidatorWallet.tap(5)
    eventually() {
      val currentInstant = svc.participantClientWithAdminToken.ledger_api.time.get().toInstant
      getOpenIssuingRounds(currentInstant).map(_.data.round.number) shouldBe Seq(1, 2)
      aliceValidatorWallet.list().coins should have length 2
      aliceValidatorWallet
        .listValidatorRewardCoupons() should have length 3
      aliceValidatorWallet
        .listAppRewardCoupons() should have length 3
    }

    clue("rewards from round 1 are merged.") {
      // Note that the rewards from round 2 are not merged as transfers allow at most 4 inputs
      // and the rewards from round 1 are prioritized
      p2pTransfer(
        aliceValidator,
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )
      eventually() {
        aliceValidatorWallet.list().coins should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 3) should have length 1
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 3) should have length 1
      }
    }

    advanceRoundsByOneTick

    clue("rewards from round 2 are merged but not round 3") {
      p2pTransfer(
        aliceValidator,
        aliceValidatorWallet,
        aliceWallet,
        alice,
        5,
      )
      eventually() {
        val currentInstant = svc.participantClientWithAdminToken.ledger_api.time.get().toInstant
        getOpenIssuingRounds(currentInstant).map(_.data.round.number) shouldBe Seq(2, 3)
        // As the max number of input is reduced to 3
        // only 3 inputs are used: 1 coin, ValidatorRewardCoupon from round 2 and AppRewardCoupon from round 2
        aliceValidatorWallet.list().coins should have length 1
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 1) should have length 0
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 2) should have length 0
        aliceValidatorWallet
          .listValidatorRewardCoupons()
          .filter(_.payload.round.number == 3) should have length 1
        aliceValidatorWallet
          .listAppRewardCoupons()
          .filter(_.payload.round.number == 3) should have length 1

      }
    }
  }

  "adjust maxNumInput if there is a tap operation in the batch" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()
    aliceValidatorWallet.tap(50)
    p2pTransfer(
      aliceValidator,
      aliceValidatorWallet,
      aliceWallet,
      alice,
      5,
    )
    eventually() {
      aliceValidatorWallet.list().coins should have length 1
    }
    aliceValidatorWallet.tap(50)

    eventually() {
      aliceValidatorWallet.list().coins should have length 2
      aliceValidatorWallet.listValidatorRewardCoupons() should have length 1
      aliceValidatorWallet.listAppRewardCoupons() should have length 1
    }

    // advancing three rounds so the rewards are collectable.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {
        aliceValidatorWallet.tap(1)
        eventually() {
          aliceValidatorWallet.list().coins should have length 3
          aliceValidatorWallet.listValidatorRewardCoupons() should have length 1
          aliceValidatorWallet.listAppRewardCoupons() should have length 1
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
            "with inputs List\\(InputCoin\\(.*\\), InputCoin\\(.*\\), InputAppRewardCoupon\\(.*\\)\\)"
          )
        )
      },
    )

  }

  "ignore expired-coins in the treasury service input" in { implicit env =>
    val (_, bob) = onboardAliceAndBob()

    aliceWallet.tap(100)

    // creating 5 soon-to-be-expired coins because the 'expire coin' automation expires
    // 4 coins at once by default & so even in the case it starts expiring coins, we have one unexpired coin for the test.
    // If this test flakes because the automation already expired all expired coins, increase the number of
    // soon-to-be-expired coins we create here
    (1 to 5).map(_ => aliceWallet.tap(CNNodeUtil.defaultHoldingFee.rate))

    eventually() {
      aliceWallet.list().coins should have length 6
    }

    // after one more tick, the coins have no value and should be ignored.
    advanceRoundsByOneTick

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {
        p2pTransfer(
          aliceValidator,
          aliceWallet,
          bobWallet,
          bob,
          1,
        )
        eventually() {
          bobWallet.balance().unlockedQty should be > BigDecimal(0)
          // there is still >1 coin
          aliceWallet.list().coins.size should be > 1
        }
      },
      entries => {
        forAtLeast(
          1,
          entries,
        )(
          // but only the non-expired coin is selected as input.
          _.message should include regex (
            "with inputs List\\(InputCoin\\(.*\\)\\)"
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
      aliceWallet.tap(5)

      clue("check that the cache is used when tapping twice in a row.") {
        loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          {
            aliceWallet.tap(5)
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

      val Seq(_, issuingRound1) = sv1Scan.getOpenAndIssuingMiningRounds()._2

      clue("create issuing round 2") {
        advanceRoundsByOneTick
      }

      clue("check that issuing round 1 is cached") {

        loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          {
            aliceWallet.tap(5)
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

  private def getOpenIssuingRounds(now: Instant)(implicit env: CNNodeTestConsoleEnvironment) = {
    val issuingRounds = getSortedIssuingRounds(svc.participantClientWithAdminToken, svcParty)
    issuingRounds.filter(r => now.isAfter(r.data.opensAt))
  }

  private def createRewardsInRound(
      validator: ValidatorAppBackendReference,
      validatorWallet: WalletAppClientReference,
      userWallet: WalletAppClientReference,
      receiverParty: PartyId,
      round: Int,
  ) = {
    p2pTransfer(
      validator,
      validatorWallet,
      userWallet,
      receiverParty,
      5,
    )

    eventually() {
      validatorWallet.list().coins should have length 1
      validatorWallet
        .listValidatorRewardCoupons()
        .filter(_.payload.round.number == round) should have length 1
      validatorWallet
        .listAppRewardCoupons()
        .filter(_.payload.round.number == round) should have length 1
    }
  }
}
