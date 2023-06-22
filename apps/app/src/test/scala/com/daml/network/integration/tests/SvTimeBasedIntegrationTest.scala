package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.round.*
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.CNNodeUtil.defaultIssuanceCurve
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.math.RoundingMode
import java.time.Duration as JavaDuration
import scala.jdk.CollectionConverters.*
import scala.util.Random

class SvTimeBasedIntegrationTest extends SvTimeBasedIntegrationTestBase {

  "SVs collect SvcReward and SvReward automatically" in { implicit env =>
    initSvc()

    eventually() {
      val rounds = getSortedOpenMiningRounds(svc.participantClientWithAdminToken, svcParty)
      rounds should have size 3
      svs.map { sv =>
        val coins = sv.participantClient.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv.getSvcInfo().svParty)
        coins shouldBe empty
      }
    }

    // one tick - round 0 closes.
    advanceRoundsByOneTick
    val config = defaultIssuanceCurve.initialValue
    val RoundsPerYear =
      BigDecimal(365 * 24 * 60 * 60).bigDecimal.divide(BigDecimal(150.0).bigDecimal)
    val coinsToIssueToSvc = config.coinToIssuePerYear
      .multiply(
        BigDecimal(1.0).bigDecimal
          .subtract(config.appRewardPercentage)
          .subtract(config.validatorRewardPercentage)
      )
      .divide(RoundsPerYear, RoundingMode.HALF_UP)
    eventually() {
      getSortedIssuingRounds(svc.participantClientWithAdminToken, svcParty) should have size 1

      // Only Sv1 get svc reward from round 0 as Sv2, Sv3 and Sv4 only joined in round 3
      inside(
        svs.head.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(svs.head.getSvcInfo().svParty)
      ) { case Seq(newCoin) =>
        newCoin.data.svc shouldBe svcParty.toProtoPrimitive
        newCoin.data.owner shouldBe svs.head.getSvcInfo().svParty.toProtoPrimitive
        newCoin.data.amount.initialAmount shouldBe coinsToIssueToSvc
          .setScale(10, RoundingMode.HALF_UP)
      }

      Seq(sv2, sv3, sv4).map { sv =>
        val coins = sv.participantClient.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv.getSvcInfo().svParty)
        coins shouldBe empty
      }
    }

    // three ticks - rounds 1-3 close.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    eventually() {
      getSortedIssuingRounds(svc.participantClientWithAdminToken, svcParty) should have size 3

      val eachSvGetInRound3 =
        coinsToIssueToSvc
          .divide(BigDecimal(svs.size).bigDecimal, RoundingMode.HALF_UP)
          .setScale(10, RoundingMode.HALF_UP)

      // All Svs get reward from round 3
      inside(
        sv1.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(svs.head.getSvcInfo().svParty)
      ) { case Seq(_, _, _, newCoin) =>
        newCoin.data.svc shouldBe svcParty.toProtoPrimitive
        newCoin.data.owner shouldBe sv1.getSvcInfo().svParty.toProtoPrimitive
        newCoin.data.amount.initialAmount shouldBe eachSvGetInRound3
      }

      Seq(sv2, sv3, sv4).map { sv =>
        inside(
          sv.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cc.coin.Coin.COMPANION)(sv.getSvcInfo().svParty)
        ) { case Seq(newCoin) =>
          newCoin.data.svc shouldBe svcParty.toProtoPrimitive
          newCoin.data.owner shouldBe sv.getSvcInfo().svParty.toProtoPrimitive
          newCoin.data.amount.initialAmount shouldBe eachSvGetInRound3
        }
      }
    }
  }

  "SvReward collection works even if some rewards are too old to collect" in { implicit env =>
    initSvc()
    val sv4Party = sv4.getSvcInfo().svParty
    clue("Stopping SV4 so it can't collect its rewards") {
      sv4.stop()
    }
    actAndCheck(
      "Advancing by 15 rounds",
      (1 to 14).foreach(_ => advanceRoundsByOneTick),
    )(
      "SV4 has 11 unclaimed rewards contracts",
      _ => {
        sv4.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvReward.COMPANION)(
            svcParty
          )
          .filter(_.data.sv == sv4Party.toProtoPrimitive) should have length 11
        sv4.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv4Party) should have length 0
      },
    )
    actAndCheck(
      "SV4 starts again",
      sv4.startSync(),
    )(
      "SV4 collects all rewards it can and leaves the one it can't",
      _ => {
        sv4.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvReward.COMPANION)(
            svcParty
          ) should have length 1
        sv4.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv4Party) should have length 10
      },
    )
    actAndCheck(
      "We advance by another round",
      advanceRoundsByOneTick,
    )(
      // TODO(#5059) Implement expiry of SV rewards that are too old
      "The new reward gets collected by SV4, the old ones still remain (until we implement expiry)",
      _ => {
        sv4.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvReward.COMPANION)(
            svcParty
          ) should have length 1
        sv4.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cc.coin.Coin.COMPANION)(sv4Party) should have length 11
      },
    )
  }

  "calculation of issuance per coin" in { implicit env =>
    initSvc()

    // 3 unfeatured app rewards & 3 featured app rewards & 3 validator rewards, 2 of each for round 0 and one for round 1
    // to check we sum up but only for the right round.
    val rewards = Seq(
      // featured app rewards for a total of 200.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(1.0).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(199.0).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(3.0).bigDecimal,
        new Round(1),
      ),
      // unfeatured app rewards for a total of 9800.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(2.5).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(9797.5).bigDecimal,
        new Round(0),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(5.0).bigDecimal,
        new Round(1),
      ),
      // validator rewards for a total of 10000.0 in round 0
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(3.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(9997.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(15.0).bigDecimal,
        new Round(1),
      ),
    )
    // Create a bunch of rewards directly
    svc.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.flatMap(_.create.commands.asScala.toSeq),
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        advanceRoundsByOneTick
        eventually() {
          getSortedIssuingRounds(svc.participantClientWithAdminToken, svcParty) should have size 1
        }
      },
      entries =>
        // we should expect logs of creating confirmation by at least 3 SV which is the required number of confirmations.
        forAtLeast(3, entries)(
          _.message should include(
            s"created confirmation for summarizing mining round with com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary(10000.0000000000, 200.0000000000, 9800.0000000000)"
          )
        ),
    )

    def decimal(d: Double): java.math.BigDecimal = BigDecimal(d).setScale(10).bigDecimal

    val issuingRounds = getSortedIssuingRounds(svc.participantClientWithAdminToken, svcParty)

    inside(issuingRounds) { case Seq(issuingRound) =>
      issuingRound.data.issuancePerValidatorRewardCoupon shouldBe decimal(0.2000000000)
      issuingRound.data.issuancePerFeaturedAppRewardCoupon shouldBe decimal(100.0000000000)
      issuingRound.data.issuancePerUnfeaturedAppRewardCoupon shouldBe decimal(0.6000000000)
    }
  }

  "collect expired reward coupons" in { implicit env =>
    def getRewardCoupons(
        round: Contract[OpenMiningRound.ContractId, OpenMiningRound]
    ) = {
      svc.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(AppRewardCoupon.COMPANION)(
          svcParty,
          co => co.data.round.number == round.payload.round.number,
        ) ++
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorRewardCoupon.COMPANION)(
            svcParty,
            co => co.data.round.number == round.payload.round.number,
          )
    }
    initSvc()
    startAllSync(aliceValidator, bobValidator)

    val round =
      sv1Scan.getTransferContextWithInstances(getLedgerTime).latestOpenMiningRound.contract
    // There may be rewards left over from other tests, so we first check the
    // contract IDs of existing ones, and compare to that below
    val leftoverRewardIds = getRewardCoupons(round).view.map(_.id).toSet

    val (aliceParty, bobParty) = onboardAliceAndBob()
    aliceWallet.tap(100.0)
    bobWallet.tap(100.0)

    actAndCheck(
      "Generate some reward coupons by executing a few direct transfers", {
        p2pTransfer(aliceValidator, aliceWallet, bobWallet, bobParty, 10.0)
        p2pTransfer(aliceValidator, aliceWallet, bobWallet, bobParty, 10.0)
        p2pTransfer(bobValidator, bobWallet, aliceWallet, aliceParty, 10.0)
        p2pTransfer(bobValidator, bobWallet, aliceWallet, aliceParty, 10.0)
      },
    )(
      "Wait for all reward coupons to be created",
      _ => {
        advanceTimeByPollingInterval(sv1)
        getRewardCoupons(round)
          .filterNot(c =>
            leftoverRewardIds(c.id)
          ) should have length 8 // 4 app rewards + 4 validator
      },
    )

    actAndCheck(
      "Advance 5 ticks, to close the round",
      (1 to 5).foreach(_ => advanceRoundsByOneTick),
    )(
      "Wait for all unclaimed coupons to be archived and the closed round to be archived",
      _ => {
        advanceTimeByPollingInterval(sv1)
        getRewardCoupons(round) shouldBe empty
        sv1Scan
          .getClosedRounds()
          .filter(r => r.payload.round.number == round.payload.round.number) should be(empty)
      },
    )
  }

  "expire stale `SvOnboarding` contracts" in { implicit env =>
    clue("Initialize SVC with 3 SVs") {
      startAllSync(svc, sv1Scan, sv1, sv2, sv3, sv1Validator, sv2Validator, sv3Validator)
      sv1.getSvcInfo().svcRules.payload.members should have size 3
    }
    clue(
      "Add a phantom SV and stop SV3 so that SV4 can't gather enough confirmations just yet"
    ) {
      actAndCheck(
        "Add phantom Sv and stop sv3", {
          addPhantomSv()
          sv3.stop()
        },
      )("there are 4 members", _ => sv1.getSvcInfo().svcRules.payload.members should have size 4)
      // We now need 3 confirmations to execute an action, but only sv1 and sv2 are active.
    }
    clue("SV4 starts") {
      sv4Validator.start()
      sv4.start()
    }
    clue("An `SvOnboarding` contract is created") {
      eventually()(
        // The onboarding is requested by SV4 during SvApp init.
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) should have length 1
      )
    }
    actAndCheck("No onboarding happens for a long time", advanceTime(JavaDuration.ofHours(25)))(
      "The `SvOnboarding` contract expires and is archived",
      _ =>
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingRequest.COMPANION)(svcParty) shouldBe empty,
    )
  }

  "expire stale `SvOnboardingConfirmed` contracts" in { implicit env =>
    clue("Initialize SVC with 3 SVs") {
      startAllSync(svc, sv1Scan, sv1, sv2, sv3, sv1Validator, sv2Validator, sv3Validator)
      sv1.getSvcInfo().svcRules.payload.members should have size 3
    }
    val svXParty = allocateRandomSvParty("svX")
    actAndCheck(
      "Create a new `SvOnboardingConfirmed` Contract with new party \"svX\"",
      svc.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = sv1
          .getSvcInfo()
          .svcRules
          .contractId
          .exerciseSvcRules_ConfirmSvOnboarding(
            svXParty.toProtoPrimitive,
            "new random party",
            "create new `SvOnboardingConfirmed` contract",
          )
          .commands
          .asScala
          .toSeq,
      ),
    )(
      "SvX's `SvOnboardingConfirmed` contract is created'",
      _ =>
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(
            svcParty
          ) should have length 1,
    )
    actAndCheck(
      "No confirmation happens within 24h",
      advanceTime(JavaDuration.ofHours(25)),
    )(
      "The `SvOnboardingConfirmed` contract expires and is archived",
      _ =>
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svonboarding.SvOnboardingConfirmed.COMPANION)(svcParty) shouldBe empty,
    )
  }

  "archive expired `ValidatorOnboarding` contracts" in { implicit env =>
    clue("Initialize SVC with 1 SV") {
      startAllSync(svc, sv1Scan, sv1, sv1Validator)
      sv1.getSvcInfo().svcRules.payload.members should have size 1
    }
    val testCandidateSecret = Random.alphanumeric.take(10).mkString
    actAndCheck(
      "create a new `ValidatorOnboarding` contract", {
        val validatorOnboarding = new cn.validatoronboarding.ValidatorOnboarding(
          sv1.getSvcInfo().svParty.toProtoPrimitive,
          testCandidateSecret,
          svc.participantClientWithAdminToken.ledger_api.time.get().toInstant.plusSeconds(3600),
        ).create.commands.asScala.toSeq

        sv1.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          actAs = Seq(sv1.getSvcInfo().svParty),
          optTimeout = None,
          commands = validatorOnboarding,
        )
      },
    )(
      "The `ValidatorOnboarding` contract exists.",
      _ =>
        sv1
          .listOngoingValidatorOnboardings()
          .filter(e => e.payload.candidateSecret == testCandidateSecret) should have size 1,
    )
    actAndCheck(
      "No confirmation happens within 2h",
      advanceTime(JavaDuration.ofHours(2)),
    )(
      "The `ValidatorOnboarding` contract expires and is archived",
      _ =>
        sv1
          .listOngoingValidatorOnboardings()
          .filter(e => e.payload.candidateSecret == testCandidateSecret) should have size 0,
    )
  }

  "auto-merge unclaimed rewards" in { implicit env =>
    initSvc()

    val threshold =
      10 // TODO(M3-46): base this on the actual threshold read from the svcRules config
    val numRewards = threshold + 1
    val rewardAmount = 0.1

    def getUnclaimedRewardContracts() =
      svc.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(UnclaimedReward.COMPANION)(svcParty)

    val existingUnclaimedRewards = getUnclaimedRewardContracts().length

    actAndCheck(
      s"Create as many unclaimed rewards as needed to have at least ${numRewards}", {
        val unclaimedRewards = ((existingUnclaimedRewards + 1) to numRewards).map(_ =>
          new UnclaimedReward(svcParty.toProtoPrimitive, BigDecimal(rewardAmount).bigDecimal)
        )
        if (!unclaimedRewards.isEmpty) {
          svc.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
            actAs = Seq(svcParty),
            optTimeout = None,
            commands = unclaimedRewards.flatMap(_.create.commands.asScala.toSeq),
          )
        }
      },
    )(
      "Wait for the unclaimed rewards to get merged automagically",
      _ => {
        advanceTimeByPollingInterval(svc)
        getUnclaimedRewardContracts().length should (be < threshold)
      },
    )
  }

  "expire stale `Confirmation` contracts" in { implicit env =>
    clue("Initialize SVC with 4 SVs") {
      startAllSync(
        svc,
        sv1Scan,
        sv1,
        sv2,
        sv3,
        sv4,
        sv1Validator,
        sv2Validator,
        sv3Validator,
        sv4Validator,
      )
      sv1.getSvcInfo().svcRules.payload.members should have size 4
    }
    clue(
      "Stop three SVs so that actions can't gather enough confirmations"
    ) {
      sv2.stop()
      sv3.stop()
      sv4.stop()
      sv1.getSvcInfo().svcRules.payload.members should have size 4
      // We now need 3 confirmations to execute an action, but only sv1 is active.
    }

    clue(
      "Sync with background automation that onboards validator"
    ) {
      eventually()({
        val rounds = getSortedOpenMiningRounds(svc.participantClientWithAdminToken, svcParty)
        rounds should have size 3
      })
    }

    val confirmationCid = actAndCheck(
      "Wait for one tick",
      advanceTime(tickDurationWithBuffer),
    )(
      "Find confirmation (for issuing rounds)",
      _ => {
        val contractList = svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action.toValue.getConstructor() == "ARC_CoinRules")
        contractList should have length 1
        contractList(0).id
      },
    )

    val bufferDurationInSeconds = 20L

    actAndCheck(
      "Wait for Confirmation TTL to elapse",
      advanceTime(
        SvUtil
          .fromRelTime(SvUtil.defaultSvcRulesConfig().actionConfirmationTimeout)
          .plus(JavaDuration.ofSeconds(bufferDurationInSeconds))
      ),
    )(
      "The Confirmation expires and is archived",
      _ => {
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action.toValue.getConstructor() == "ARC_CoinRules")
          .filter(_.id == confirmationCid) should have length 0
      },
    )

    actAndCheck(
      "Wait for one polling period",
      advanceTimeByPollingInterval(sv1),
    )(
      "Find new confirmation (for issuing rounds)",
      _ => {
        val contractList = svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
          .filter(_.data.action.toValue.getConstructor() == "ARC_CoinRules")
        contractList should have length 1
      },
    )

  }

  "detect an inactive leader" in { implicit env =>
    val svcRulesBeforeElection = clue("Initialize SVC with 4 SVs") {
      startAllSync(
        svc,
        sv1Scan,
        sv1,
        sv2,
        sv3,
        sv4,
        sv1Validator,
        sv2Validator,
        sv3Validator,
        sv4Validator,
      )
      val svcRulesBeforeElection = svc.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
        .head
        .data
      svcRulesBeforeElection.members should have size 4
      svcRulesBeforeElection
    }

    var rounds: Seq[OpenMiningRound.Contract] = Seq.empty[OpenMiningRound.Contract]

    clue(
      "Wait for first three rounds to be opened"
    ) {
      eventually()({
        rounds = getSortedOpenMiningRounds(svc.participantClientWithAdminToken, svcParty)
        rounds should have size 3
      })
    }

    clue(
      "Stop the leader so we can detect its inactivity later"
    ) {
      sv1.stop()
    }

    clue(
      "Advance time such that a new round should be opened. SVs should start their checks of the leader's inactivity"
    ) {
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          advanceTime(tickDurationWithBuffer)
        },
        entries => {
          forExactly(3, entries) { line =>
            line.message should include(
              "Starting check for leader inactivity"
            )
          }
        },
      )
    }

    clue(
      "A new leader is elected and leader-based triggers resume operating normally"
    ) {
      // It doesn't really matter which sv we pick
      val automationConfig = sv2.config.automation
      val effectiveTimeout = SvUtil
        .fromRelTime(SvUtil.defaultSvcRulesConfig().leaderInactiveTimeout)
        .plus(automationConfig.pollingInterval.asJava)

      val bufferDuration = JavaDuration.ofSeconds(5)

      advanceTime(effectiveTimeout.plus(bufferDuration))
      eventually() {
        val svcRulesAfterElection = svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
          .head
          .data
        svcRulesAfterElection.epoch shouldBe svcRulesBeforeElection.epoch + 1
        svcRulesAfterElection.leader should not be svcRulesBeforeElection.leader
      }

      eventually() {
        val newRounds = svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(OpenMiningRound.COMPANION)(
            svcParty,
            c => !rounds.contains(c),
          )
        newRounds.length should be >= 1
      }
    }
  }
}
