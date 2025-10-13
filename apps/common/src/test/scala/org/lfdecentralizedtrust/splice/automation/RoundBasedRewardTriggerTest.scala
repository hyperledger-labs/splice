package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

class RoundBasedRewardTriggerTest
    extends AsyncWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext {

  private val clock = new SimClock(loggerFactory = loggerFactory)

  private def testTrigger(
      tasks: Seq[(RoundBasedRewardTriggerTest.TestTask, TaskOutcome)]*
  ) = {
    // we always duplicate the first call as it can be used to do the scheduling
    new TestTrigger(mutable.Queue(tasks.prepended(tasks.head)*))
  }

  "reward trigger" should {

    "run task in the future based on the open interval" in {
      val now = clock.now.toInstant
      val futureDate = now.plusSeconds(10)
      val maxScheduleDate = futureDate.plusSeconds(10)
      val task1 =
        RoundBasedRewardTriggerTest.TestTask(
          1,
          futureDate,
          maxScheduleDate,
          futureDate.plusSeconds(20),
        )
      val trigger = testTrigger(
        Seq(
          (task1, TaskNoop)
        )
      )
      trigger.performWorkIfAvailable().futureValue
      trigger.timesTriggerRan shouldBe 0
      clock.advance(8.seconds.toJava)
      trigger.performWorkIfAvailable().futureValue
      trigger.timesTriggerRan shouldBe 0
      clock.advance(13.seconds.toJava)
      trigger.performWorkIfAvailable().futureValue
      trigger.timesTriggerRan shouldBe 1
      trigger.lastRunAt.value should be >= futureDate
    }

    "run tasks immediately if they are overdue" in {
      val now = clock.now.toInstant
      val pastDate = now.minusSeconds(10)
      val task1 =
        RoundBasedRewardTriggerTest.TestTask(1, pastDate, now, now.minusSeconds(5))
      val trigger = testTrigger(
        Seq(
          (task1, TaskNoop)
        )
      )

      trigger.performWorkIfAvailable().futureValue
      trigger.timesTriggerRan shouldBe 1
    }

    "run until the trigger reports no work done" in {
      val now = clock.now.toInstant
      val pastDate = now.minusSeconds(10)
      val task1 =
        RoundBasedRewardTriggerTest.TestTask(1, pastDate, now, now.minusSeconds(5))
      val trigger = testTrigger(
        Seq(
          (task1, TaskSuccess("did work"))
        ),
        Seq(
          (task1, TaskNoop)
        ),
        // will not actually be executed
        Seq(
          (task1, TaskNoop)
        ),
      )

      for {
        _ <- trigger.performWorkIfAvailable()
        _ <- trigger.performWorkIfAvailable()
        result <- trigger.performWorkIfAvailable()
        resultAfter <- trigger.performWorkIfAvailable()
      } yield {
        result shouldBe false
        resultAfter shouldBe false
        // first task which is duplicated and the first noop task
        trigger.timesTriggerRan shouldBe 3
      }
    }

    "run just once for a round after the trigger reports no work done " in {
      val now = clock.now.toInstant
      val pastDate = now.minusSeconds(10)
      val task1 =
        RoundBasedRewardTriggerTest.TestTask(1, pastDate, now, now.minusSeconds(5))
      val trigger = testTrigger(
        Seq(
          (task1, TaskNoop)
        )
      )

      for {
        result <- trigger.performWorkIfAvailable()
        _ <- trigger.performWorkIfAvailable()
      } yield {
        result shouldBe false
        trigger.timesTriggerRan shouldBe 1
      }
    }

    "run for future round scheduling if it already ran for previous round" in {
      val now = clock.now.toInstant
      val pastDate = now.minusSeconds(10)
      val task1 =
        RoundBasedRewardTriggerTest.TestTask(1, pastDate, now, now.minusSeconds(5))
      val trigger = testTrigger(
        Seq(
          (task1, TaskNoop)
        )
      )

      for {
        result <- trigger.performWorkIfAvailable()
        _ <- trigger.performWorkIfAvailable()
      } yield {
        result shouldBe false
        trigger.timesTriggerRan shouldBe 1
      }
    }

    "run based on the newest round which was not executed" in {
      val now = clock.now.toInstant
      val pastDate = now.minusSeconds(10)
      val task1 =
        RoundBasedRewardTriggerTest.TestTask(1, pastDate, now, now.minusSeconds(5))
      val trigger = testTrigger(
        Seq(
          (task1, TaskNoop)
        ),
        Seq(
          (task1, TaskNoop),
          (
            task1.copy(roundNumber = 2, opensAt = now, scheduleAtMaxTime = now.plusSeconds(20)),
            TaskNoop,
          ),
        ),
        Seq(
          (task1, TaskNoop),
          (
            task1.copy(roundNumber = 2, opensAt = now, scheduleAtMaxTime = now.plusSeconds(20)),
            TaskNoop,
          ),
        ),
      )

      // process task1
      trigger.performWorkIfAvailable().futureValue
      trigger.timesTriggerRan shouldBe 1
      // do nothing
      trigger.performWorkIfAvailable().futureValue
      trigger.timesTriggerRan shouldBe 1
      // do nothing but schedule for task2
      trigger.performWorkIfAvailable().futureValue
      trigger.timesTriggerRan shouldBe 1
      clock.advance(21.seconds.toJava)
      // run task1 and task2
      trigger.performWorkIfAvailable().futureValue
      trigger.timesTriggerRan shouldBe 3
    }
  }

  private val lf = loggerFactory
  private class TestTrigger(
      tasks: mutable.Queue[Seq[(RoundBasedRewardTriggerTest.TestTask, TaskOutcome)]]
  ) extends RoundBasedRewardTrigger[RoundBasedRewardTriggerTest.TestTask] {

    private val lastRunTime = new AtomicReference[Option[Instant]](None)
    private val tasksRunningFor =
      new AtomicReference[Seq[(RoundBasedRewardTriggerTest.TestTask, TaskOutcome)]](Seq.empty)
    private val tasksProcessed = new AtomicInteger(0)

    def lastRunAt: Option[Instant] = lastRunTime.get()
    def timesTriggerRan: Int = tasksProcessed.get()

    protected def retrieveAvailableTasksForRound()(implicit
        tc: TraceContext
    ): Future[Seq[RoundBasedRewardTriggerTest.TestTask]] = {
      val tasksToRunFor = tasks.dequeue()
      tasksRunningFor.set(tasksToRunFor)
      Future.successful(tasksToRunFor.map(_._1))
    }

    protected def completeTask(task: RoundBasedRewardTriggerTest.TestTask)(implicit
        tc: TraceContext
    ): Future[TaskOutcome] = {
      lastRunTime.set(Some(clock.now.toInstant))
      tasksProcessed.incrementAndGet()
      Future.successful(tasksRunningFor.get().find(_._1 == task).map(_._2).value)
    }

    protected def isStaleTask(task: RoundBasedRewardTriggerTest.TestTask)(implicit
        tc: TraceContext
    ): Future[Boolean] = Future.successful(false)

    protected def context: TriggerContext = {
      TriggerContext(
        AutomationConfig(
        ),
        clock,
        clock,
        TriggerEnabledSynchronization.Noop,
        RetryProvider(
          lf,
          ProcessingTimeout(),
          FutureSupervisor.Noop,
          NoOpMetricsFactory,
        ),
        lf,
        NoOpMetricsFactory,
      )
    }
  }

}

object RoundBasedRewardTriggerTest {
  case class TestTask(
      roundNumber: Long,
      opensAt: Instant,
      scheduleAtMaxTime: Instant,
      closesAt: Instant,
  ) extends RoundBasedRewardTrigger.RoundBasedTask
      with PrettyPrinting {
    override def pretty: Pretty[RoundBasedRewardTriggerTest.TestTask] = prettyOfClass()
  }
}
