package com.daml.network.sv.automation.singlesv.membership.onboarding

import cats.implicits.showInterpolator
import com.daml.network.automation.{TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.config.CNThresholds
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.automation.singlesv.membership.{
  SvcRulesTopologyStateReconciler,
  SvTopologyStatePollingAndAssignedTrigger,
}
import com.daml.network.sv.automation.singlesv.membership.onboarding.SequencerOnboarding.SequencerToOnboard
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{DomainId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional

class SequencerOnboarding(
    svParty: PartyId,
    val svSvcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
    logger: TracedLogger,
) extends SvcRulesTopologyStateReconciler[SequencerToOnboard] {
  override def diffSvcRulesWithTopology(
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules]
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[SequencerToOnboard]] = {
    participantAdminConnection.getSequencerDomainState(svcRules.domain).map {
      sequencerDomainState =>
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
        val sequencersToAdd =
          configuredSequencers
            .filterNot(sequencerDomainState.mapping.active.contains)
            .map(SequencerToOnboard(svcRules.domain, _))
        val thresholdToSet =
          CNThresholds.sequencerConnectionsSizeThreshold(configuredSequencers.size)

        if (sequencersToAdd.nonEmpty) {
          logger.info {
            import com.daml.network.util.PrettyInstances.prettyCodegenContractId
            import com.digitalasset.canton.util.ShowUtil.showPretty
            show"Planning to add sequencers $sequencersToAdd to match SvcRules ${svcRules.contractId}"
          }
          sequencersToAdd
        } else if (thresholdToSet != sequencerDomainState.mapping.threshold) {
          // This case can occur because we reset the threshold at the end of our tests (see ResetSequencerDomainState)
          // We try to add an already onboarded sequencer once more to force a topology tx that would reset the threshold.
          logger.info(
            s"Planning to change sequencer threshold from ${sequencerDomainState.mapping.threshold} to ${thresholdToSet}"
          )
          sequencerDomainState.mapping.active
            .take(1)
            .map(SequencerToOnboard(svcRules.domain, _))
        } else Seq()
    }
  }

  override def reconcileTask(
      task: SequencerToOnboard
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[TaskOutcome] = {
    logger.info(show"Adding sequencer $task")
    participantAdminConnection
      .ensureSequencerDomainStateAddition(
        task.domainId,
        task.sequencerId,
        svParty.uid.namespace.fingerprint,
        RetryFor.Automation,
      )
      .map(_ => TaskSuccess(s"Added sequencer $task"))
  }
}

object SequencerOnboarding {
  case class SequencerToOnboard(
      domainId: DomainId,
      sequencerId: SequencerId,
  ) extends PrettyPrinting {

    override def pretty: Pretty[SequencerToOnboard.this.type] = prettyOfClass(
      param("domainId", _.domainId),
      param("sequencerId", _.sequencerId),
    )
  }
}

/** Onboards a new sequencer to the current global domain topology state.
  * The onboarding only happens if the following conditions are met:
  * - the sequencer is configured in the domain config found in the SvcRules
  */
class SvOnboardingSequencerTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SvTopologyStatePollingAndAssignedTrigger[SequencerToOnboard](
      context,
      store,
    ) {

  override val reconciler = new SequencerOnboarding(
    store.key.svParty,
    store,
    participantAdminConnection,
    logger,
  )

}
