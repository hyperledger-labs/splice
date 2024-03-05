package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.syntax.traverse.*
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.SequencerAdminConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** This trigger currently relies on enough SVs working on the same set traffic balance request around the same time.
  * TODO(#10597): Remove this constraint by retrying the reconciliation task every setBalanceRequestSubmissionWindowSize if not successful.
  */
class SvOnboardingUnlimitedTrafficTrigger2(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    sequencerAdminConnection: SequencerAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SvOnboardingUnlimitedTrafficTrigger2.Task] {

  import SvOnboardingUnlimitedTrafficTrigger2.Task

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      svcRules <- svcStore.getSvcRules()
      svMembersWithTrafficState <- SvUtil.listActiveSvParticipantsAndMediators(svcRules).traverse {
        memberId =>
          for {
            state <- sequencerAdminConnection.lookupSequencerTrafficControlState(memberId)
          } yield memberId -> state
      }
    } yield {
      svMembersWithTrafficState.collect {
        case (memberId, None) => Task(memberId)
        case (memberId, Some(result))
            if result.trafficState.extraTrafficLimit.fold(NonNegativeLong.zero)(
              _.toNonNegative
            ) != NonNegativeLong.maxValue =>
          Task(memberId)
      }
    }
  }

  override protected def completeTask(task: SvOnboardingUnlimitedTrafficTrigger2.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      _ <- sequencerAdminConnection.ensureSequencerTrafficControlState(
        task.memberId,
        NonNegativeLong.maxValue,
      )
    } yield TaskSuccess(
      s"Updated traffic limit for ${task.memberId} to NonNegativeLong.maxValue"
    )

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = for {
    svcRules <- svcStore.getSvcRules()
    trafficState <- sequencerAdminConnection.lookupSequencerTrafficControlState(task.memberId)
  } yield {
    !SvUtil.listActiveSvParticipantsAndMediators(svcRules).contains(task.memberId)
    || trafficState
      .flatMap(_.trafficState.extraTrafficLimit)
      .fold(NonNegativeLong.zero)(_.toNonNegative) == NonNegativeLong.maxValue
  }

}

object SvOnboardingUnlimitedTrafficTrigger2 {
  final case class Task(
      memberId: Member
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("memberId", _.memberId)
      )
  }
}
