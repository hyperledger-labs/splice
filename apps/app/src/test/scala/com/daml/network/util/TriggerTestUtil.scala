package com.daml.network.util

import com.daml.network.automation.Trigger
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

}
