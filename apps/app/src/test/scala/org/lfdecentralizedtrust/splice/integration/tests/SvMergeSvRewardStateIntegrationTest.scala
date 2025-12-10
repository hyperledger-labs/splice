package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.Identifier
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.MergeSvRewardStateContractsTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil.resumeAllDsoDelegateTriggers

import scala.jdk.CollectionConverters.*

class SvMergeSvRewardStateIntegrationTest extends SvIntegrationTestBase with TriggerTestUtil {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      // Single SV to allow direct ledger API submissions as the DSO
      // to create SvRewardState contracts.
      .simpleTopology1Sv(this.getClass.getSimpleName)

  override protected lazy val sanityChecksIgnoredRootCreates: Seq[Identifier] = Seq(
    SvRewardState.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  "Multiple SvRewardStates for the same SV get merged" in { implicit env =>
    val dso = sv1Backend.getDsoInfo().dsoParty

    def getRewardStates() =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(SvRewardState.COMPANION)(
          dso,
          _ => true,
        )

    val rewardStates = getRewardStates()
    val rewardState = inside(rewardStates) { case Seq(rewardState) =>
      rewardState.data.svName shouldBe "Digital-Asset-2"
      rewardState
    }

    clue("Pause ReceiveSvRewardCouponTrigger to avoid races") {
      // ReceiveSvRewardCouponTrigger and MergeSvRewardStateContractsTrigger can race to modify
      // the same SvRewardState contracts (`LOCAL_VERDICT_LOCKED_CONTRACTS`),
      // leading to trigger retries and non-deterministic output of the loggerFactory.assertLogs below.
      activeSvs.map(_.dsoAutomation.trigger[ReceiveSvRewardCouponTrigger]).foreach(_.pause())
    }

    setTriggersWithin(
      triggersToPauseAtStart =
        activeSvs.map(_.dsoDelegateBasedAutomation.trigger[MergeSvRewardStateContractsTrigger]),
      triggersToResumeAtStart = Seq.empty,
    ) {
      actAndCheck(
        "Create a duplicate SvRewardStateContract",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          Seq(dso),
          commands = rewardState.data.create().commands.asScala.toSeq,
        ),
      )(
        "Two reward states get created",
        _ => {
          val newRewardStates = getRewardStates()
          newRewardStates should have size 2
        },
      )
      loggerFactory.assertLogs(
        {
          resumeAllDsoDelegateTriggers[MergeSvRewardStateContractsTrigger]
          clue("Trigger merges SvRewardState contracts") {
            eventually() {
              val newRewardStates = getRewardStates()
              newRewardStates should have size 1
            }
          }
        },
        _.warningMessage should include(
          "SV Digital-Asset-2 has 2 SvRewardState contracts, this likely indicates a bug"
        ),
      )
    }
  // Not testing reward state contracts for different sv names, this is covered through every test with multiple SVs anyway.
  }
}
