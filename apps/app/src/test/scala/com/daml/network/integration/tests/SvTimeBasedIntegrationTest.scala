package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.round.*
import com.daml.network.codegen.java.cn
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.time.Duration as JavaDuration
import scala.jdk.CollectionConverters.*
import CNNodeTests.BracketSynchronous.*
import com.daml.network.codegen.java.cn.svcrules.SvReward

class SvTimeBasedIntegrationTest extends SvTimeBasedIntegrationTestBaseWithSharedEnvironment {
  "auto-merge unclaimed rewards" in { implicit env =>
    val threshold =
      10 // TODO(M3-46): base this on the actual threshold read from the svcRules config
    val numRewards = threshold + 1
    val rewardAmount = 0.1

    def getUnclaimedRewardContracts() =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(UnclaimedReward.COMPANION)(svcParty)

    val existingUnclaimedRewards = getUnclaimedRewardContracts().length

    actAndCheck(
      s"Create as many unclaimed rewards as needed to have at least $numRewards", {
        val unclaimedRewards = ((existingUnclaimedRewards + 1) to numRewards).map(_ =>
          new UnclaimedReward(svcParty.toProtoPrimitive, BigDecimal(rewardAmount).bigDecimal)
        )
        if (!unclaimedRewards.isEmpty) {
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
            actAs = Seq(svcParty),
            optTimeout = None,
            commands = unclaimedRewards.flatMap(_.create.commands.asScala.toSeq),
          )
        }
      },
    )(
      "Wait for the unclaimed rewards to get merged automagically",
      _ => {
        advanceTimeByPollingInterval(sv1Backend)
        getUnclaimedRewardContracts().length should (be < threshold)
      },
    )
  }

  "expire stale `Confirmation` contracts" in { implicit env =>
    bracket(
      {
        sv2Backend.stop()
        sv3Backend.stop()
        sv4Backend.stop()
      },
      startAllSync(
        sv2Backend,
        sv3Backend,
        sv4Backend,
      ),
    ) {
      sv1Backend.getSvcInfo().svcRules.payload.members should have size 4

      // We now need 3 confirmations to execute an action, but only sv1 is active.
      clue(
        "Sync with background automation that onboards validator"
      ) {
        eventually()({
          val rounds =
            getSortedOpenMiningRounds(sv1Backend.participantClientWithAdminToken, svcParty)
          rounds should have size 3
        })
      }

      val confirmationCid = actAndCheck(
        "Wait for one tick",
        advanceTime(tickDurationWithBuffer),
      )(
        "Find confirmation (for issuing rounds)",
        _ => {
          val contractList = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
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
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
            .filter(_.data.action.toValue.getConstructor() == "ARC_CoinRules")
            .filter(_.id == confirmationCid) should have length 0
        },
      )

      actAndCheck(
        "Wait for one polling period",
        advanceTimeByPollingInterval(sv1Backend),
      )(
        "Find new confirmation (for issuing rounds)",
        _ => {
          val contractList = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svcrules.Confirmation.COMPANION)(svcParty)
            .filter(_.data.action.toValue.getConstructor() == "ARC_CoinRules")
          contractList should have length 1
        },
      )
    }
  }

  "detect an inactive leader" in { implicit env =>
    val svcRulesBeforeElection = clue("Ensure 4 SVs are initialized") {
      val svcRulesBeforeElection =
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
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
        rounds = getSortedOpenMiningRounds(sv1Backend.participantClientWithAdminToken, svcParty)
        rounds should have size 3
      })
    }

    // Stop the leader so we can detect its inactivity later
    bracket(sv1Backend.stop(), sv1Backend.startSync()) {
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
        val automationConfig = sv2Backend.config.automation
        val effectiveTimeout = SvUtil
          .fromRelTime(SvUtil.defaultSvcRulesConfig().leaderInactiveTimeout)
          .plus(automationConfig.pollingInterval.asJava)

        val bufferDuration = JavaDuration.ofSeconds(5)

        advanceTime(effectiveTimeout.plus(bufferDuration))
        eventually() {
          val svcRulesAfterElection =
            sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
              .head
              .data
          svcRulesAfterElection.epoch shouldBe svcRulesBeforeElection.epoch + 1
          svcRulesAfterElection.leader should not be svcRulesBeforeElection.leader
        }

        eventually() {
          val newRounds = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(OpenMiningRound.COMPANION)(
              svcParty,
              c => !rounds.contains(c),
            )
          newRounds.length should be >= 1
        }
      }

      clue("All `SvReward` are collected") {
        eventually() {
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(SvReward.COMPANION)(
              svcParty
            ) shouldBe empty
        }
      }
    }
  }
}
