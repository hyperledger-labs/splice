package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.cometbft.{
  CometBftConfig,
  CometBftNodeConfig,
  GovernanceKeyConfig,
  SequencingKeyConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  ScanConfig,
  SynchronizerNodeConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_AddSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_AddSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SvOnboardingUnlimitedTrafficTrigger
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}

import java.util.Optional
import scala.jdk.CollectionConverters.*

/** Tests that SV automation triggers can complete the reward
  * pipeline for the initial round when bootstrapping TBAR at a
  * non-zero round with BFT f >= 1.
  *
  * Adds a dummy 5th SV to increase the BFT quorum from 1 to 2.
  */
class NonZeroRoundBootstrapBftTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  private val initialRound = 4815L

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialRound = initialRound)
        )(config)
      )
      .addConfigTransform((_, config) => ConfigTransforms.withNoVoteCooldown(config))

  "SV triggers complete the reward pipeline for the initial round" in { implicit env =>
    import definitions.GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsOk
    import definitions.GetRewardAccountingActivityTotalsResponse.members.RewardAccountingActivityTotalsCannotProvide

    // Pause the trigger before adding the dummy. Real SVs already
    // have unlimited traffic by test-body start (SV app init awaits
    // it via waitForSvToObtainUnlimitedTraffic), so pausing early has
    // no effect on them. The dummy's participant doesn't exist on the
    // sequencer, so without this pause the trigger polls indefinitely
    // logging "does not yet have a traffic state".
    env.svs.local.foreach(
      _.dsoAutomation.trigger[SvOnboardingUnlimitedTrafficTrigger].pause().futureValue
    )

    // Add a dummy 5th SV with a fake scan URL BEFORE any round
    // advancement. This raises BFT f from 0 to 1, so the initial
    // round's reward pipeline runs under the higher quorum.
    addDummySvWithFakeScanUrl()

    // With f=1 in the augmented DsoRules, BFT reads normally need
    // 2 agreeing Ok responses. Only SV1 returns Ok; SV2/3/4 return
    // CannotProvide (dropped from `n`), and the dummy's connection
    // fails to open (kept in `n` as possibly-disagreeing via
    // scanConnections.failed). The two-phase probe-filter-consensus
    // computes `n = withData.size + unavailable` = 1 + 1 = 2, so
    // f = 0 and SV1's single Ok is quorum — the pipeline completes
    // at bootstrap.
    advanceTimeForRewardAutomationToRunForCurrentRound
    actAndCheck(
      "Advance to next round opening",
      advanceRoundsToNextRoundOpening,
    )(
      "SV1's scan has reward totals and the initial round is issuing",
      _ => {
        sv1ScanBackend
          .getRewardAccountingActivityTotals(initialRound) shouldBe a[
          RewardAccountingActivityTotalsOk
        ]

        // The round becoming IssuingMiningRound is DSO-level proof:
        // under BFT f=1, each SV's SummarizingMiningRoundTrigger
        // must obtain reward-accounting totals via the two-phase
        // BFT read described above. If that failed, fewer than
        // f+1=2 SVs could submit summaries and the round would not
        // advance.
        val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        issuingRounds.exists(
          _.payload.round.number == initialRound
        ) shouldBe true

        // SV2's scan does NOT have local reward activity data for
        // the initial round — only SV1's scan seeded it. SV2's SV
        // trigger obtained the totals via the two-phase BFT read,
        // but the scan HTTP endpoint queries the local store, so it
        // returns CannotProvide.
        sv2ScanBackend
          .getRewardAccountingActivityTotals(initialRound) shouldBe a[
          RewardAccountingActivityTotalsCannotProvide
        ]
      },
    )

    // Advance two ticks so that verdict ingestion processes batches
    // that see OpenMiningRound(initialRound+1) already archived.
    // A single tick archives the round, but the verdict batch for
    // that tick may be processed before the rewards reference store
    // has indexed the archival — so lookupLatestArchivedOpenMiningRound
    // returns None and last_archived_round stays at initialRound.
    // The second tick generates new verdicts that find the archival
    // already indexed, bumping last_archived_round.
    advanceTimeAndWaitForRoundOpening
    advanceTimeAndWaitForRoundOpening

    // Wait for RewardComputationTrigger to compute totals for the
    // next round now that last_archived_round covers it.
    eventually() {
      sv1ScanBackend
        .getRewardAccountingActivityTotals(initialRound + 1) shouldBe a[
        RewardAccountingActivityTotalsOk
      ]
    }

  // TODO(#6690): Non-firstSV scans currently seed
  // earliest_ingested_round from the first verdict batch they
  // observe, not from initialRound — so RewardComputationTrigger
  // can skip rounds around the bootstrap on SV2/3/4 even when the
  // BFT reads work. Once the non-firstSV activity-meta seeding is
  // symmetric with SV1's (see
  // DbAppActivityRecordStore.insertAppActivityRecordsDBIO),
  // re-tighten the assertion by adding a check on SV2's local
  // reward totals for initialRound+1.
  }

  private def addDummySvWithFakeScanUrl()(implicit
      env: SpliceTestConsoleEnvironment
  ): com.digitalasset.canton.topology.PartyId = {
    val dsoInfo = sv1Backend.getDsoInfo()
    val svParty = dsoInfo.svParty
    val dsoParty = dsoInfo.dsoParty

    // Random suffix avoids collisions with stale parties from
    // previous runs (databases persist between local test runs).
    val dummySvParty = sv1Backend.participantClientWithAdminToken.ledger_api.parties
      .allocate(s"dummy-sv5-${scala.util.Random.nextInt().toHexString}")
      .party

    val addSvAction = new ARC_DsoRules(
      new SRARC_AddSv(
        new DsoRules_AddSv(
          dummySvParty.toProtoPrimitive,
          "Dummy-SV5",
          1000L,
          "PAR::dummy-sv5::dummy",
          new Round(initialRound),
        )
      )
    )

    val (_, voteRequest) = actAndCheck(
      "sv1 creates vote request to add dummy SV5",
      eventuallySucceeds() {
        sv1Backend.createVoteRequest(
          svParty.toProtoPrimitive,
          addSvAction,
          "url",
          "Add dummy SV5 for BFT threshold test",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        )
      },
    )(
      "vote request exists",
      _ => sv1Backend.listVoteRequests().loneElement,
    )

    actAndCheck(
      "sv2 and sv3 vote yes (3 votes total → executes)", {
        Seq(sv2Backend, sv3Backend).foreach { sv =>
          eventuallySucceeds() {
            sv.castVote(voteRequest.contractId, true, "url", "description")
          }
        }
      },
    )(
      "dummy SV5 is in DsoRules and raises BFT f from 0 to 1",
      _ => {
        val info = sv1Backend.getDsoInfo()
        info.dsoRules.payload.svs.size() shouldBe 5
      },
    )

    actAndCheck(
      "Set fake scan URL on dummy SV",
      setDummySvScanUrl(dsoParty, dummySvParty),
    )(
      "Dummy SV's scan URL is in the BFT peer list",
      _ => {
        val nodeState = sv1Backend.getDsoInfo().svNodeStates(dummySvParty)
        nodeState.payload.state.synchronizerNodes.values.asScala
          .exists(_.scan.isPresent) shouldBe true
      },
    )

    dummySvParty
  }

  private def setDummySvScanUrl(
      dsoParty: com.digitalasset.canton.topology.PartyId,
      dummySvParty: com.digitalasset.canton.topology.PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val synchronizerId = decentralizedSynchronizerId.toProtoPrimitive

    val nodeConfig = new SynchronizerNodeConfig(
      new CometBftConfig(
        Map.empty[String, CometBftNodeConfig].asJava,
        Seq.empty[GovernanceKeyConfig].asJava,
        Seq.empty[SequencingKeyConfig].asJava,
      ),
      Optional.empty(), // sequencer
      Optional.empty(), // mediator
      // Unreachable URL — BFT marks it as a failed peer, increasing
      // totalNumber (and thus f) without needing a running scan.
      Optional.of(new ScanConfig("http://localhost:1")),
      Optional.empty(), // legacySequencerConfig
      Optional.empty(), // sequencerIdentity
      Optional.empty(), // physicalSynchronizers
    )

    eventuallySucceeds() {
      val info = sv1Backend.getDsoInfo()
      val currentDsoRulesCid = info.dsoRules.contractId
      val currentNodeStateCid = info.svNodeStates
        .getOrElse(dummySvParty, fail("SvNodeState not found for dummy SV"))
        .contractId
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitJava(
          actAs = Seq(dummySvParty),
          readAs = Seq(dsoParty),
          commands = currentDsoRulesCid
            .exerciseDsoRules_SetSynchronizerNodeConfig(
              dummySvParty.toProtoPrimitive,
              synchronizerId,
              nodeConfig,
              currentNodeStateCid,
            )
            .commands()
            .asScala
            .toSeq,
        )
    }
  }
}
