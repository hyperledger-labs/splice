package com.daml.network.util

import com.daml.network.automation.Trigger

trait TriggerTestUtil {

  /** Enable/Disable triggers before executing a code block
    */
  def setTriggersWithin[T](
      triggersToPauseAtStart: Seq[Trigger],
      triggersToResumeAtStart: Seq[Trigger],
  )(codeBlock: => T) = {
    try {
      triggersToPauseAtStart.map(_.pause())
      triggersToResumeAtStart.map(_.resume())
      codeBlock
    } finally {
      triggersToPauseAtStart.map(_.resume())
      triggersToResumeAtStart.map(_.pause())
    }
  }

}
