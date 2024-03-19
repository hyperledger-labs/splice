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
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** This trigger currently relies on enough SVs working on the same set traffic balance request around the same time.
  * TODO(#10597): Remove this constraint by retrying the reconciliation task every setBalanceRequestSubmissionWindowSize if not successful.
  */
class SvOnboardingUnlimitedTrafficTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    sequencerAdminConnectionO: Option[SequencerAdminConnection],
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SvOnboardingUnlimitedTrafficTrigger.Task] {

  import SvOnboardingUnlimitedTrafficTrigger.Task

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      svcRulesAndStates <- svcStore.getSvcRulesWithMemberNodeStates()
      svMembersWithTrafficState <- svcRulesAndStates.activeSvParticipantAndMediatorIds().traverse {
        memberId =>
          for {
            state <- sequencerAdminConnection.getSequencerTrafficControlState(memberId)
          } yield memberId -> state
      }
    } yield {
      svMembersWithTrafficState.sortBy(_._1).collect {
        case (memberId, trafficState)
            if trafficState.extraTrafficLimit != NonNegativeLong.maxValue =>
          Task(memberId)
      }
    }
  }

  override protected def completeTask(task: SvOnboardingUnlimitedTrafficTrigger.Task)(implicit
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
    svcRulesAndStates <- svcStore.getSvcRulesWithMemberNodeStates()
    trafficState <- sequencerAdminConnection.getSequencerTrafficControlState(task.memberId)
  } yield {
    !svcRulesAndStates.activeSvParticipantAndMediatorIds().contains(task.memberId)
    || trafficState.extraTrafficLimit == NonNegativeLong.maxValue
  }

  private def sequencerAdminConnection = sequencerAdminConnectionO.getOrElse(
    throw Status.FAILED_PRECONDITION
      .withDescription("No sequencer admin connection configured for SV App")
      .asRuntimeException()
  )

}

object SvOnboardingUnlimitedTrafficTrigger {
  final case class Task(
      memberId: Member
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("memberId", _.memberId)
      )
  }
}
