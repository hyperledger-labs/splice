package com.daml.network.sv.automation.singlesv.offboarding

import cats.implicits.showInterpolator
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svc.globaldomain.DomainNodeConfig
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.topology.MediatorId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.OptionConverters.RichOptional

/** Offboard a mediator from the current global domain topology state.
  * The offboarding happens if the mediator is present in the topology state but not in SvcRules.
  * We deliberately do not go through off-boarded member since contrary to the party the mediator id is mutable
  * so for a single SV we would then need to track a list of mediator ids that have been used in the past which gets quite messy.
  * Relying on the diff between topology state and SvcRules does allow for a race:
  * An SV might have seen an updated topology state but not yet the updated SvcRules. However for the topology state to be updated a threshold of SVs must have seen the SvcRules change first so the offboarding proposal will just die.
  */
class SvOffboardingMediatorTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[MediatorId] {

  private val svParty = svcStore.key.svParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[MediatorId]] = {
    for {
      svcRules <- svcStore.getSvcRules()
      currentMediatorState <- participantAdminConnection.getMediatorDomainState(
        svcRules.domain
      )
    } yield {
      val svcRulesCurrentMediators = getMediatorIds(
        svcRules.contract.payload.members
          .values()
          .asScala
          .flatMap(_.domainNodes.values().asScala)
      )
      currentMediatorState.mapping.active
        .filterNot(svcRulesCurrentMediators.contains)
    }
  }

  override protected def completeTask(task: MediatorId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      svcRules <- svcStore.getSvcRules()
      _ <- participantAdminConnection.ensureMediatorDomainStateRemovalProposal(
        svcRules.domain,
        task,
        svParty.uid.namespace.fingerprint,
        RetryFor.Automation,
      )
    } yield {
      TaskSuccess(show"Removed mediator $task from domain ${svcRules.domain}")
    }
  }

  override protected def isStaleTask(task: MediatorId)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  private def getMediatorIds(
      members: Iterable[DomainNodeConfig]
  )(implicit tc: TraceContext) = {
    members
      .flatMap(_.mediator.toScala)
      .map(_.mediatorId)
      .flatMap(mediatorId =>
        MediatorId
          .fromProtoPrimitive(mediatorId, "mediatorId")
          .fold(
            error => {
              logger.warn(s"Failed to parse mediator id $mediatorId. $error")
              None
            },
            Some(_),
          )
      )
      .toSeq
  }
}
