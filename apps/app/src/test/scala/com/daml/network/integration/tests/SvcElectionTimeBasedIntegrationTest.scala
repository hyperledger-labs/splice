package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.codegen.{Created, Update}
import com.daml.network.codegen.java.cc.round.*
import com.daml.network.codegen.java.cn
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.slf4j.event.Level
import CNNodeTests.BracketSynchronous.*
import com.daml.network.codegen.java.cn.svcrules.ElectionRequest

import java.time.Duration as JavaDuration
import scala.jdk.CollectionConverters.*

class SvcElectionTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironmentWithElections {

  def createElectionRequestUpdate(
      svc: PartyId,
      requester: PartyId,
      epoch: Long,
      reason: cn.svcrules.ElectionRequestReason,
      ranking: Seq[PartyId],
  ): Update[Created[ElectionRequest.ContractId]] =
    new cn.svcrules.ElectionRequest(
      svc.toProtoPrimitive,
      requester.toProtoPrimitive,
      epoch,
      reason,
      ranking.map(_.toProtoPrimitive).asJava,
    ).create

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
    // It doesn't really matter which sv we pick
    val pollingIntervalDuration = sv2Backend.config.automation.pollingInterval.asJava
    val sv1Party = sv1Backend.getSvcInfo().svParty

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
            // TODO(#6856) Consider reverting this to a `forExactly`
            forAtLeast(3, entries) { line =>
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
        val effectiveTimeout = SvUtil
          .fromRelTime(SvUtil.defaultSvcRulesConfig().leaderInactiveTimeout)
          .plus(pollingIntervalDuration)

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

    clue("Create an outdated request") {
      val reason = new cn.svcrules.electionrequestreason.ERR_LeaderUnavailable(
        com.daml.ledger.javaapi.data.Unit.getInstance()
      )
      val ranking = Seq(
        sv1Backend,
        sv2Backend,
        sv3Backend,
        sv4Backend,
      ).map(_.getSvcInfo().svParty)

      sv1Backend.participantClient.ledger_api_extensions.commands.submitWithResult(
        userId = sv1Backend.config.ledgerApiUser,
        actAs = Seq(svcParty),
        readAs = Seq.empty,
        update = createElectionRequestUpdate(
          svcParty,
          sv1Party,
          svcRulesBeforeElection.epoch,
          reason,
          ranking,
        ),
      )
    }

    clue("Verify that outdated election requests are expired") {
      advanceTime(pollingIntervalDuration)
      eventually() {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.ElectionRequest.COMPANION)(
            svcParty,
            { co => co.data.requester == sv1Party.toProtoPrimitive },
          ) shouldBe empty
      }
    }
  }
}
