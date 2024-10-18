package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.Identifier
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.MergeSvRewardStateContractsTrigger
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil

import scala.jdk.CollectionConverters.*

class SvMergeSvRewardStateIntegrationTest extends SvIntegrationTestBase with TriggerTestUtil {

  override def environmentDefinition =
    EnvironmentDefinition
      // Single SV to allow direct ledger API submissions as the DSO
      // to create SvRewardState contracts.
      .simpleTopology1Sv(this.getClass.getSimpleName)

  override protected lazy val updateHistoryIgnoredRootCreates: Seq[Identifier] = Seq(
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
    setTriggersWithin(
      triggersToPauseAtStart =
        Seq(sv1Backend.dsoDelegateBasedAutomation.trigger[MergeSvRewardStateContractsTrigger]),
      triggersToResumeAtStart = Seq.empty,
    ) {
      actAndCheck(
        "Create a duplicate SvRewardStateContract",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          Seq(dso),
          optTimeout = None,
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
          sv1Backend.dsoDelegateBasedAutomation.trigger[MergeSvRewardStateContractsTrigger].resume()
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
