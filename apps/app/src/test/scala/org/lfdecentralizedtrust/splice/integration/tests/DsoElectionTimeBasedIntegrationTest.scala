package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_OffboardSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_OffboardSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.BracketSynchronous.bracket
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.CloseVoteRequestTrigger
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.slf4j.event.Level

import java.time.Duration as JavaDuration
import scala.concurrent.duration.DurationInt

class DsoElectionTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironmentWithElections {

  private val dummyDsoSynchronizerId = SynchronizerId.tryFromString("domain1::domain")

  // TODO(#7649): once flow is fixed add test to check that SVs can elect a new delegate (currently locked contract issue)
  "SVs can elect a new delegate and if a delegate gets offboarded a new delegate is chosen while current ElectionRequests are archived" in {
    implicit env =>
      clue("Initialize DSO with 4 SVs") {
        startAllSync(
          sv1ScanBackend,
          sv2ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv4Backend,
        )
        val dsoRulesBeforeElection =
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(splice.dsorules.DsoRules.COMPANION)(dsoParty)
            .head
            .data
        dsoRulesBeforeElection.svs should have size 4
      }

      val pollingIntervalDuration = sv2Backend.config.automation.pollingInterval.asJava
      val sv1Party = sv1Backend.getDsoInfo().svParty

      actAndCheck(
        "Create a new election request", {
          val ranking = Vector(
            sv2Backend,
            sv1Backend,
            sv3Backend,
            sv4Backend,
          ).map(_.getDsoInfo().svParty.toProtoPrimitive)
          Seq(sv1Backend).map(backend =>
            backend.createElectionRequest(backend.getDsoInfo().svParty.toProtoPrimitive, ranking)
          )
        },
      )(
        "Verify that election requests is created",
        _ => {
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(splice.dsorules.ElectionRequest.COMPANION)(
              dsoParty,
              { co => co.data.requester == sv1Party.toProtoPrimitive },
            ) should have size 1
        },
      )

      clue("remove current delegate such that dsoRules epoch is incremented") {
        val currentLeader = sv1Backend.getDsoInfo().dsoRules.payload.dsoDelegate
        val leaderBackend = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
          .find(
            _.getDsoInfo().svParty.toProtoPrimitive == currentLeader
          )
          .value
        val removeAction = new ARC_DsoRules(
          new SRARC_OffboardSv(
            new DsoRules_OffboardSv(
              currentLeader
            )
          )
        )
        val (_, voteRequest) = actAndCheck(
          "Creating vote request",
          eventuallySucceeds() {
            sv1Backend.createVoteRequest(
              sv1Party.toProtoPrimitive,
              removeAction,
              "url",
              "remove current delegate",
              sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
              None,
            )
          },
        )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)
        Seq(sv2Backend, sv3Backend)
          .filter(
            // current delegate not voting
            _.getDsoInfo().svParty.toProtoPrimitive != currentLeader
          )
          .foreach { sv =>
            clue(s"${sv.name} accepts vote") {
              eventuallySucceeds() {
                sv.castVote(
                  voteRequest.contractId,
                  isAccepted = true,
                  "url",
                  "description",
                )
              }
            }
          }
        clue("Leader has changed") {
          loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
            leaderBackend.dsoDelegateBasedAutomation
              .trigger[CloseVoteRequestTrigger]
              .resume(),
            entries => {
              forExactly(4, entries) { line =>
                line.message should include(
                  "Noticed an DsoRules epoch change"
                )
              }
            },
          )
          sv2Backend.getDsoInfo().dsoRules.payload.dsoDelegate should not be currentLeader
        }
      }
      clue("Sv1 stops") {
        sv1Backend.stop()
      }
      clue("Verify that the election request are outdated and expired") {
        advanceTime(pollingIntervalDuration)
        eventually() {
          sv2Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(splice.dsorules.ElectionRequest.COMPANION)(
              dsoParty,
              { co => co.data.requester == sv1Party.toProtoPrimitive },
            ) shouldBe empty
        }
      }
  }

  // TODO(#7649): enable test back if automatic delegate election is re-enabled in new flow
  "detect an inactive delegate" ignore { implicit env =>
    val dsoRulesBeforeElection = clue("Initialize DSO with 4 SVs") {
      startAllSync(
        sv1ScanBackend,
        sv2ScanBackend,
        sv1Backend,
        sv2Backend,
        sv3Backend,
        sv4Backend,
      )
      val dsoRulesBeforeElection =
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(splice.dsorules.DsoRules.COMPANION)(dsoParty)
          .head
          .data
      dsoRulesBeforeElection.svs should have size 4
      dsoRulesBeforeElection
    }

    var rounds: Seq[OpenMiningRound.Contract] = Seq.empty[OpenMiningRound.Contract]
    // It doesn't really matter which sv we pick
    val pollingIntervalDuration = sv2Backend.config.automation.pollingInterval.asJava

    clue(
      "Wait for first three rounds to be opened"
    ) {
      eventually()({
        rounds = getSortedOpenMiningRounds(sv1Backend.participantClientWithAdminToken, dsoParty)
        rounds should have size 3
      })
    }

    // Stop the delegate so we can detect its inactivity later
    bracket(
      sv1Backend.stop(),
      // when starting up, eventually SV1 will find out it was replaced as delegate
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
        sv1Backend.startSync(),
        entries => {
          forExactly(1, entries) { line =>
            line.loggerName should include("SV=sv1")
            line.message should include(
              "Noticed an DsoRules epoch change"
            )

          }
        },
        timeUntilSuccess = 1.minute,
      ),
    ) {
      clue(
        "Advance time such that a new round should be opened. SVs should start their checks of the delegate's inactivity"
      ) {
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          {
            advanceTime(tickDurationWithBuffer)
          },
          entries => {
            // TODO(#6856) Consider reverting this to a `forExactly`
            forAtLeast(3, entries) { line =>
              line.message should include(
                "Starting check for delegate inactivity"
              )
            }
          },
        )
      }

      clue(
        "A new delegate is elected and delegate-based triggers resume operating normally"
      ) {
        val effectiveTimeout = SvUtil
          .fromRelTime(
            SvUtil.defaultDsoRulesConfig(dummyDsoSynchronizerId).dsoDelegateInactiveTimeout
          )
          .plus(pollingIntervalDuration)

        val bufferDuration = JavaDuration.ofSeconds(5)

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          advanceTime(effectiveTimeout.plus(bufferDuration)),
          entries => {
            forExactly(3, entries) { line =>
              line.message should include(
                "Noticed an DsoRules epoch change"
              )
            }
          },
        )
        val dsoRulesAfterElection =
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(splice.dsorules.DsoRules.COMPANION)(dsoParty)
            .head
            .data
        dsoRulesAfterElection.epoch shouldBe dsoRulesBeforeElection.epoch + 1
        dsoRulesAfterElection.dsoDelegate should not be dsoRulesBeforeElection.dsoDelegate

        eventually() {
          val newRounds = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(OpenMiningRound.COMPANION)(
              dsoParty,
              c => !rounds.contains(c),
            )
          newRounds.length should be >= 1
        }
      }
    }
  }
}
