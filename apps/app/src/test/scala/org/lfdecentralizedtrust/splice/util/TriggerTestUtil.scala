package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.{BaseTest, ScalaFuturesWithPatience}
import com.typesafe.scalalogging.LazyLogging
import org.lfdecentralizedtrust.splice.automation.{Trigger, UpdateIngestionService}
import org.lfdecentralizedtrust.splice.console.ScanAppBackendReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition.sv1Backend
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

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
  // Note: using `def`, as the trigger may be destroyed and recreated
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

  def pauseScanIngestionWithin[T](scan: ScanAppBackendReference)(codeBlock: => T): T = {
    try {
      logger.info(s"Pausing ingestion for ${scan.name}")
      scan.automation.services[UpdateIngestionService].foreach(_.pause().futureValue)
      codeBlock
    } finally {
      logger.info(s"Resuming ingestion for ${scan.name}")
      scan.automation.services[UpdateIngestionService].foreach(_.resume())
    }
  }
}

object TriggerTestUtil extends ScalaFuturesWithPatience with LazyLogging {

  /** Enable/Disable triggers before executing a code block
    */
  def setTriggersWithin[T](
      triggersToPauseAtStart: Seq[Trigger] = Seq.empty,
      triggersToResumeAtStart: Seq[Trigger] = Seq.empty,
  )(codeBlock: => T): T = {
    try {
      logger.info(s"Pausing triggers for block: $triggersToPauseAtStart")
      logger.info(s"Resuming triggers for block: $triggersToResumeAtStart")
      triggersToPauseAtStart.foreach(_.pause().futureValue)
      triggersToResumeAtStart.foreach(_.resume())
      codeBlock
    } finally {
      logger.info(s"Resuming triggers after block: $triggersToPauseAtStart")
      logger.info(s"Pausing triggers after block: $triggersToResumeAtStart")
      triggersToPauseAtStart.foreach(_.resume())
      triggersToResumeAtStart.foreach(_.pause().futureValue)
    }
  }

  def pauseAllDsoDelegateTriggers[T <: Trigger](implicit
      tag: ClassTag[T],
      env: SpliceTestConsoleEnvironment,
  ): Unit = {
    env.svs.local.foreach(
      _.dsoDelegateBasedAutomation.trigger[T].pause().futureValue
    )
  }

  def resumeAllDsoDelegateTriggers[T <: Trigger](implicit
      tag: ClassTag[T],
      env: SpliceTestConsoleEnvironment,
  ): Unit = {
    env.svs.local.foreach(
      _.dsoDelegateBasedAutomation.trigger[T].resume()
    )
  }
}
