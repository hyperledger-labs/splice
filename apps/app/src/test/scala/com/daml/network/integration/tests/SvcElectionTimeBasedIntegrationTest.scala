package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.round.*
import com.daml.network.codegen.java.cn
import com.daml.network.sv.util.SvUtil

import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import CNNodeTests.BracketSynchronous.*

import java.time.Duration as JavaDuration

class SvcElectionTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironmentWithElections {

  "detect an inactive leader" in { implicit env =>
    val svcRulesBeforeElection = clue("Initialize SVC with 4 SVs") {
      startAllSync(
        sv1ScanBackend,
        sv1Backend,
        sv2Backend,
        sv3Backend,
        sv4Backend,
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
      )
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
    }
  }
}
