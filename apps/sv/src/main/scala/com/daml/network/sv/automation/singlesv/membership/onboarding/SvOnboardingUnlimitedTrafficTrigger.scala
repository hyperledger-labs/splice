package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.syntax.traverseFilter.*
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.SequencerAdminConnection
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** This trigger currently relies on enough SVs working on the same set traffic balance request around the same time,
  * as the trigger works with limited parallelism.
  *
  * TODO(tech-debt): remove this constraint by ensuring that we regularly submit set-traffic-balance requests for ALL members.
  */
class SvOnboardingUnlimitedTrafficTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    sequencerAdminConnectionO: Option[SequencerAdminConnection],
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration,
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
      svMembersWithTrafficState <- svcRulesAndStates
        .activeSvParticipantAndMediatorIds()
        .traverseFilter { memberId =>
          for {
            stateO <- sequencerAdminConnection.lookupSequencerTrafficControlState(memberId)
          } yield {
            if (stateO.isEmpty) {
              // This can happen for mediators which are registered in SvcRules before they connect.
              logger.info(s"Member $memberId does not yet have a traffic state, skipping")
            }
            stateO.map(memberId -> _)
          }
        }
    } yield {
      // Sorting here so we have a better chance of all SVs working on the same set traffic balance request around the same time.
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
      // We must read the state here again to pick up on new serials
      currentStatus <- sequencerAdminConnection.getSequencerTrafficControlState(task.memberId)
      _ <- sequencerAdminConnection.setSequencerTrafficControlState(
        currentStatus,
        NonNegativeLong.maxValue,
        context.pollingClock,
        trafficBalanceReconciliationDelay,
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
