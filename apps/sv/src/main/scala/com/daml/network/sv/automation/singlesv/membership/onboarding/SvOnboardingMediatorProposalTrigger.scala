package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.implicits.showInterpolator
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.topology.MediatorId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional

/** Onboards a new mediator to the current global domain topology state.
  * The onboarding only happens if the following conditions are met:
  * - the mediator is configured in the domain config found in the SvcRules
  * - there is already a proposal for the mediator. This is required to ensure that we onboard only when the
  * candidate requests for it to be onboarded, to avoid onboarding the mediator before the sequencer is initialized.
  * If we onboard it before the sequencer is initialized the sequencer will not have the topology state for the identity
  * of the mediator and initializing the mediator will fail.
  */
class SvOnboardingMediatorProposalTrigger(
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
      mediatorStateProposals <- participantAdminConnection.getMediatorDomainStateProposals(
        svcRules.domain
      )
      currentMediatorState <- participantAdminConnection.getMediatorDomainState(
        svcRules.domain
      )
    } yield {
      val mediatorsProposedForOnboarding = mediatorStateProposals
        .map { proposal =>
          proposal.mapping.allMediatorsInGroup.diff(
            currentMediatorState.mapping.allMediatorsInGroup
          )
        }
        // we currently support onboarding just one a time in the topology state
        .collect { case Seq(mediator) =>
          mediator
        }
      val currentDomainConfigs =
        OnboardingDomainNodeUtil.currentDomainConfig(svcRules.domain, svcRules)
      val domainNodeConfiguredMediators = currentDomainConfigs
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
      mediatorsProposedForOnboarding.filter(domainNodeConfiguredMediators.contains)
    }
  }

  override protected def completeTask(task: MediatorId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      svcRules <- svcStore.getSvcRules()
      _ <- participantAdminConnection.ensureMediatorDomainStateAdditionProposal(
        svcRules.domain,
        task,
        svParty.uid.namespace.fingerprint,
        RetryFor.Automation,
      )
    } yield {
      TaskSuccess(show"Added mediator $task to domain ${svcRules.domain}")
    }
  }

  // proposing is safe and it checks when running the task so no need to duplicate the same check here
  override protected def isStaleTask(task: MediatorId)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)
}
