// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv.onboarding

import cats.implicits.showInterpolator
import com.daml.network.automation.{TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.config.Thresholds
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.automation.singlesv.onboarding.SequencerOnboarding.SequencerToOnboard
import com.daml.network.sv.automation.singlesv.{
  SvTopologyStatePollingAndAssignedTrigger,
  DsoRulesTopologyStateReconciler,
}
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.store.DsoRulesStore.DsoRulesWithSvNodeStates
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
    val svDsoStore: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    logger: TracedLogger,
) extends DsoRulesTopologyStateReconciler[SequencerToOnboard] {
  override def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesWithSvNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[SequencerToOnboard]] = {
    val dsoRules = dsoRulesAndState.dsoRules
    participantAdminConnection.getSequencerDomainState(dsoRules.domain).map {
      sequencerDomainState =>
        val currentSynchronizerConfigs = dsoRulesAndState.currentSynchronizerNodeConfigs()
        val configuredSequencers =
          currentSynchronizerConfigs
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
            .map(SequencerToOnboard(dsoRules.domain, _))
        val thresholdToSet =
          Thresholds.sequencerConnectionsSizeThreshold(configuredSequencers.size)

        if (sequencersToAdd.nonEmpty) {
          logger.info {
            show"Planning to add sequencers $sequencersToAdd to match $dsoRulesAndState"
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
            .map(SequencerToOnboard(dsoRules.domain, _))
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
  * - the sequencer is configured in the domain config found in the DsoRules
  */
class SvOnboardingSequencerTrigger(
    baseContext: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SvTopologyStatePollingAndAssignedTrigger[SequencerToOnboard](
      baseContext,
      store,
    ) {

  override val reconciler = new SequencerOnboarding(
    store.key.svParty,
    store,
    participantAdminConnection,
    logger,
  )

}
