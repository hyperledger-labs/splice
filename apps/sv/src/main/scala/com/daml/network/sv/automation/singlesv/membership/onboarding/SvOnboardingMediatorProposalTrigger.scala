package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.implicits.{catsSyntaxTuple2Semigroupal, showInterpolator}
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.MemberIdUtil
import com.digitalasset.canton.topology.MediatorId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional

/** Onboards a new mediator to the current global domain topology state.
  * The onboarding only happens if the following conditions are met:
  * - the mediator is configured in the domain config found in the SvcRules
  *  - the sequencer configured alongside the mediator is already part of the topology state.
  *      This is required for the mediator to be able to read from an existing sequencer counter.
  *      This also requires that the sequencer is bootstrapped at exactly the topology transaction that added it.
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
      currentMediatorState <- participantAdminConnection.getMediatorDomainState(
        svcRules.domain
      )
      currentSequencerState <- participantAdminConnection.getSequencerDomainState(
        svcRules.domain
      )
    } yield {
      val currentDomainConfigs =
        OnboardingDomainNodeUtil.currentDomainConfig(svcRules.domain, svcRules)
      val domainNodeConfiguredNodes = currentDomainConfigs
        .flatMap(domainConfigs =>
          (domainConfigs.mediator.toScala -> domainConfigs.sequencer.toScala).tupled
        )
        .map { case (mediatorConfig, sequencerConfig) =>
          val mediatorId = mediatorConfig.mediatorId
          MemberIdUtil.MediatorId
            .tryFromProtoPrimitive(mediatorId, "mediatorId")
            -> MemberIdUtil.SequencerId
              .tryFromProtoPrimitive(sequencerConfig.sequencerId, "sequencerId")
        }
      val mediatorsToAdd =
        domainNodeConfiguredNodes
          .filter { case (mediatorId, sequencerId) =>
            !currentMediatorState.mapping.active.contains(mediatorId) &&
            currentSequencerState.mapping.active.contains(sequencerId)
          }
          .map(_._1)

      if (mediatorsToAdd.nonEmpty)
        logger.info {
          import com.digitalasset.canton.util.ShowUtil.showPretty
          import com.daml.network.util.PrettyInstances.prettyCodegenContractId
          show"Planning to add mediators $mediatorsToAdd to match SvcRules ${svcRules.contractId}"
        }
      mediatorsToAdd
    }
  }

  override protected def completeTask(task: MediatorId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      svcRules <- svcStore.getSvcRules()
      _ = logger.info(show"Adding mediator $task to domain ${svcRules.domain}")
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
