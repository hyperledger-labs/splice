package com.daml.network.util

import com.daml.network.automation.Trigger
import com.daml.network.integration.CNNodeEnvironmentDefinition.sv1Backend
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.sv.automation.leaderbased.AdvanceOpenMiningRoundTrigger
import com.digitalasset.canton.BaseTest

trait TriggerTestUtil { self: BaseTest =>

  /** Enable/Disable triggers before executing a code block
    */
  def setTriggersWithin[T](
      triggersToPauseAtStart: Seq[Trigger],
      triggersToResumeAtStart: Seq[Trigger],
  )(codeBlock: => T) = {
    try {
      triggersToPauseAtStart.foreach(_.pause().futureValue)
      triggersToResumeAtStart.foreach(_.resume())
      codeBlock
    } finally {
      triggersToPauseAtStart.foreach(_.resume())
      triggersToResumeAtStart.foreach(_.pause().futureValue)
    }
  }

  // The trigger that advances rounds, running in the sv app
  // Note: using `def`, as the trigger may be destroyed and recreated (when the sv leader changes)
  private def advanceOpenMiningRoundTrigger(implicit env: CNNodeTestConsoleEnvironment) =
    sv1Backend.leaderBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

  def advanceRoundsByOneTickViaAutomation(implicit env: CNNodeTestConsoleEnvironment): Unit = {
    eventually() {
      advanceOpenMiningRoundTrigger.runOnce().futureValue should be(true)
    }
  }
}
