// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding

import cats.implicits.showInterpolator
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{SequencerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{TaskOutcome, TaskSuccess, TriggerContext}
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.store.DsoRulesStore.DsoRulesWithSvNodeStates
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SequencerOnboarding.SequencerToOnboard
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.{
  DsoRulesTopologyStateReconciler,
  SvTopologyStatePollingAndAssignedTrigger,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional

class SequencerOnboarding(
    val svDsoStore: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    logger: TracedLogger,
) extends DsoRulesTopologyStateReconciler[SequencerToOnboard] {

  override def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesWithSvNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[SequencerToOnboard]] = {
    val dsoRules = dsoRulesAndState.dsoRules
    participantAdminConnection.getSequencerSynchronizerState(dsoRules.domain, AuthorizedState).map {
      SequencerSynchronizerState =>
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
            .filterNot(SequencerSynchronizerState.mapping.active.contains)
            .map(SequencerToOnboard(dsoRules.domain, _))
        val thresholdToSet =
          Thresholds.sequencerConnectionsSizeThreshold(configuredSequencers.size)

        if (sequencersToAdd.nonEmpty) {
          logger.info {
            show"Planning to add sequencers $sequencersToAdd to match $dsoRulesAndState"
          }
          sequencersToAdd
        } else if (thresholdToSet != SequencerSynchronizerState.mapping.threshold) {
          // This case can occur because we reset the threshold at the end of our tests (see ResetSequencerSynchronizerState)
          // We try to add an already onboarded sequencer once more to force a topology tx that would reset the threshold.
          logger.info(
            s"Planning to change sequencer threshold from ${SequencerSynchronizerState.mapping.threshold} to ${thresholdToSet}"
          )
          SequencerSynchronizerState.mapping.active
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
      .ensureSequencerSynchronizerStateAddition(
        task.synchronizerId,
        task.sequencerId,
        RetryFor.Automation,
      )
      .map(_ => TaskSuccess(s"Added sequencer $task"))
  }
}

object SequencerOnboarding {
  case class SequencerToOnboard(
      synchronizerId: SynchronizerId,
      sequencerId: SequencerId,
  ) extends PrettyPrinting {

    override def pretty: Pretty[SequencerToOnboard.this.type] = prettyOfClass(
      param("synchronizerId", _.synchronizerId),
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
      Some(participantAdminConnection),
    ) {

  override val reconciler
      : org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SequencerOnboarding =
    new SequencerOnboarding(
      store,
      participantAdminConnection,
      logger,
    )

}
