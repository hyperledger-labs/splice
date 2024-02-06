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
import com.digitalasset.canton.topology.{DomainId, MediatorId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvOnboardingMediatorUnlimitedTrafficTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SvOnboardingMediatorUnlimitedTrafficTrigger.Task] {

  import SvOnboardingMediatorUnlimitedTrafficTrigger.Task

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      svcRules <- svcStore.getSvcRules()
      mediatorState <- participantAdminConnection.getMediatorDomainState(
        svcRules.domain
      )
      mediatorsWithTrafficState <- mediatorState.mapping.active.forgetNE.traverse { mediatorId =>
        for {
          state <- participantAdminConnection.lookupTrafficControlState(svcRules.domain, mediatorId)
        } yield mediatorId -> state
      }

    } yield {
      mediatorsWithTrafficState.collect {
        case (mediator, None) => Task(mediator, svcRules.domain)
        case (mediator, Some(result))
            if result.mapping.totalExtraTrafficLimit != PositiveLong.MaxValue =>
          Task(mediator, svcRules.domain)
      }
    }
  }

  override protected def completeTask(task: SvOnboardingMediatorUnlimitedTrafficTrigger.Task)(
      implicit tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      participantId <- participantAdminConnection.getParticipantId()
      _ <- participantAdminConnection.ensureTrafficControlState(
        task.domainId,
        task.mediatorId,
        Long.MaxValue,
        participantId.uid.namespace.fingerprint,
      )
    } yield TaskSuccess("")

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = for {
    mediatorState <- participantAdminConnection.getMediatorDomainState(task.domainId)
  } yield !mediatorState.mapping.active.contains(task.mediatorId)

}

object SvOnboardingMediatorUnlimitedTrafficTrigger {
  final case class Task(
      mediatorId: MediatorId,
      domainId: DomainId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("mediatorId", _.mediatorId),
        param("domainId", _.domainId),
      )
  }
}
