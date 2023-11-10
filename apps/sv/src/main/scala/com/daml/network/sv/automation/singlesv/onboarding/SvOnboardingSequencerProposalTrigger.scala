package com.daml.network.sv.automation.singlesv.onboarding

import cats.implicits.showInterpolator
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional

/** Onboards a new sequencer to the current global domain topology state.
  * The onboarding only happens if the following conditions are met:
  * - the sequencer is configured in the domain config found in the SvcRules
  */
class SvOnboardingSequencerProposalTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SequencerId] {

  private val svParty = svcStore.key.svParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SequencerId]] = {
    for {
      svcRules <- svcStore.getSvcRules()
      sequencerDomainState <- participantAdminConnection.getSequencerDomainState(svcRules.domain)
    } yield {
      val currentDomainConfigs =
        OnboardingDomainNodeUtil.currentDomainConfig(svcRules.domain, svcRules)
      val configuredSequencers =
        currentDomainConfigs
          .flatMap(_.sequencer.toScala)
          .map(_.sequencerId)
          .flatMap(sequencerId =>
            SequencerId
              .fromProtoPrimitive(sequencerId, "sequencerId")
              .fold(
                error => {
                  logger.warn(s"Failed to parse sequencer id $sequencerId. $error")
                  None
                },
                Some(_),
              )
          )
      configuredSequencers.filterNot(sequencerDomainState.mapping.active.contains)
    }
  }

  override protected def completeTask(task: SequencerId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      svcRules <- svcStore.getSvcRules()
      _ <- participantAdminConnection.ensureSequencerDomainState(
        svcRules.domain,
        task,
        svParty.uid.namespace.fingerprint,
        RetryFor.Automation,
      )
    } yield {
      TaskSuccess(show"Added sequencer $task to domain ${svcRules.domain}")
    }
  }

  // proposing is safe and it checks when running the task so no need to duplicate the same check here
  override protected def isStaleTask(task: SequencerId)(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)
}
