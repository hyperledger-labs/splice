package com.daml.network.util

import com.daml.network.automation.Trigger
import com.daml.network.integration.EnvironmentDefinition.sv1Backend
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import com.digitalasset.canton.{BaseTest, ScalaFuturesWithPatience}

import scala.concurrent.duration.FiniteDuration

trait TriggerTestUtil { self: BaseTest =>

  /** Enable/Disable triggers before executing a code block
    */
  def setTriggersWithin[T](
      triggersToPauseAtStart: Seq[Trigger] = Seq.empty,
      triggersToResumeAtStart: Seq[Trigger] = Seq.empty,
  )(codeBlock: => T): T = {
    TriggerTestUtil.setTriggersWithin(triggersToPauseAtStart, triggersToResumeAtStart)(codeBlock)
  }

  // The trigger that advances rounds, running in the sv app
  // Note: using `def`, as the trigger may be destroyed and recreated (when the sv delegate changes)
  private def advanceOpenMiningRoundTrigger(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.dsoDelegateBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

  def advanceRoundsByOneTickViaAutomation(
      timeUntilSuccess: FiniteDuration = BaseTest.DefaultEventuallyTimeUntilSuccess
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    eventually(timeUntilSuccess) {
      advanceOpenMiningRoundTrigger.runOnce().futureValue should be(true)
    }
  }
}

object TriggerTestUtil extends ScalaFuturesWithPatience {

  /** Enable/Disable triggers before executing a code block
    */
  def setTriggersWithin[T](
      triggersToPauseAtStart: Seq[Trigger] = Seq.empty,
      triggersToResumeAtStart: Seq[Trigger] = Seq.empty,
  )(codeBlock: => T): T = {
    try {
      triggersToPauseAtStart.foreach(_.pause().futureValue)
      triggersToResumeAtStart.foreach(_.resume())
      codeBlock
    } finally {
      triggersToPauseAtStart.foreach(_.resume())
      triggersToResumeAtStart.foreach(_.pause().futureValue)
    }
  }
}
