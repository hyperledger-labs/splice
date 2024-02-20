package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.codegen.{Created, Update}
import com.daml.network.codegen.java.cc.round.*
import com.daml.network.codegen.java.cn
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.{DomainId, PartyId}
import org.slf4j.event.Level
import CNNodeTests.BracketSynchronous.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.{ElectionRequest, SvcRules_RemoveMember}
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_RemoveMember
import com.daml.network.sv.automation.leaderbased.ExecuteVoteRequestActionTrigger

import java.time.Duration as JavaDuration
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.DurationInt

class SvcElectionTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironmentWithElections {

  private val dummySvcDomainId = DomainId.tryFromString("domain1::domain")

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
        sv2ScanBackend,
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
    bracket(
      sv1Backend.stop(),
      // when starting up, eventually SV1 will find out it was replaced as leader
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.WARN))(
        sv1Backend.startSync(),
        entries => {
          forExactly(1, entries) { line =>
            line.loggerName should include("SV=sv1")
            line.message should include(
              "Noticed an SvcRules epoch change"
            )

          }
        },
        timeUntilSuccess = 1.minute,
      ),
    ) {
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
          .fromRelTime(SvUtil.defaultSvcRulesConfig(dummySvcDomainId).leaderInactiveTimeout)
          .plus(pollingIntervalDuration)

        val bufferDuration = JavaDuration.ofSeconds(5)

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          advanceTime(effectiveTimeout.plus(bufferDuration)),
          entries => {
            forExactly(3, entries) { line =>
              line.message should include(
                "Noticed an SvcRules epoch change"
              )
            }
          },
        )
        val svcRulesAfterElection =
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
            .head
            .data
        svcRulesAfterElection.epoch shouldBe svcRulesBeforeElection.epoch + 1
        svcRulesAfterElection.leader should not be svcRulesBeforeElection.leader

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

    actAndCheck(
      "Create a new election request", {
        val ranking = Vector(
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv4Backend,
        ).map(_.getSvcInfo().svParty.toProtoPrimitive)
        sv1Backend.createElectionRequest(sv1Party.toProtoPrimitive, ranking)
      },
    )(
      "Verify that election requests is created",
      _ => {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.ElectionRequest.COMPANION)(
            svcParty,
            { co => co.data.requester == sv1Party.toProtoPrimitive },
          ) should have size 1
      },
    )

    clue("remove current leader such that svcRules epoch is incremented") {
      val currentLeader = sv1Backend.getSvcInfo().svcRules.payload.leader
      val leaderBackend = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
        .find(
          _.getSvcInfo().svParty.toProtoPrimitive == currentLeader
        )
        .value
      val removeAction = new ARC_SvcRules(
        new SRARC_RemoveMember(
          new SvcRules_RemoveMember(
            currentLeader
          )
        )
      )
      leaderBackend.leaderBasedAutomation
        .trigger[ExecuteVoteRequestActionTrigger]
        .pause()
        .futureValue
      val (_, voteRequest) = actAndCheck(
        "Creating vote request",
        eventuallySucceeds() {
          sv1Backend.createVoteRequest(
            sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
            removeAction,
            "url",
            "remove current leader",
            sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
          )
        },
      )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)
      Seq(sv2Backend, sv3Backend, sv4Backend)
        .filter(
          // current leader not voting
          _.getSvcInfo().svParty.toProtoPrimitive != currentLeader
        )
        .foreach { sv =>
          clue(s"${sv.name} accepts vote") {
            val svVoteRequest = eventually() {
              sv.listVoteRequests().loneElement
            }
            svVoteRequest.contractId shouldBe voteRequest.contractId
            eventuallySucceeds() {
              sv.castVote(
                svVoteRequest.contractId,
                true,
                "url",
                "description",
              )
            }
          }
        }

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        leaderBackend.leaderBasedAutomation.trigger[ExecuteVoteRequestActionTrigger].resume(),
        entries => {
          forExactly(4, entries) { line =>
            line.message should include(
              "Noticed an SvcRules epoch change"
            )
          }
        },
      )
    }

    clue("Verify that the election request are outdated and expired") {
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
