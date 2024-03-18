package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.syntax.traverse.*
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.config.RequireTypes.PositiveLong
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{DomainId, Member}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvOnboardingUnlimitedTrafficTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SvOnboardingUnlimitedTrafficTrigger.Task] {

  import SvOnboardingUnlimitedTrafficTrigger.Task

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      rulesAndStates <- svcStore.getSvcRulesWithMemberNodeStates()
      svcRules = rulesAndStates.svcRules
      svMembersWithTrafficState <- rulesAndStates.activeSvParticipantAndMediatorIds().traverse {
        memberId =>
          for {
            state <- participantAdminConnection.lookupTrafficControlState(svcRules.domain, memberId)
          } yield memberId -> state
      }
    } yield {
      svMembersWithTrafficState.collect {
        case (memberId, None) => Task(memberId, svcRules.domain)
        case (memberId, Some(result))
            if result.mapping.totalExtraTrafficLimit != PositiveLong.MaxValue =>
          Task(memberId, svcRules.domain)
      }
    }
  }

  override protected def completeTask(task: SvOnboardingUnlimitedTrafficTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      participantId <- participantAdminConnection.getParticipantId()
      _ <- participantAdminConnection.ensureTrafficControlState(
        task.domainId,
        task.memberId,
        PositiveLong.MaxValue.value,
        participantId.uid.namespace.fingerprint,
      )
    } yield TaskSuccess(
      s"Updated traffic limit for ${task.memberId} on ${task.domainId} to PositiveLong.MaxValue"
    )

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = for {
    svcRulesAndStates <- svcStore.getSvcRulesWithMemberNodeStates()
    trafficState <- participantAdminConnection.lookupTrafficControlState(
      task.domainId,
      task.memberId,
    )
  } yield {
    !svcRulesAndStates.activeSvParticipantAndMediatorIds().contains(task.memberId)
    || trafficState.fold(PositiveLong.one)(
      _.mapping.totalExtraTrafficLimit
    ) == PositiveLong.MaxValue
  }

}

object SvOnboardingUnlimitedTrafficTrigger {
  final case class Task(
      memberId: Member,
      domainId: DomainId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("memberId", _.memberId),
        param("domainId", _.domainId),
      )
  }
}
