// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding

import cats.implicits.showInterpolator
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SyncConnectionStalenessCheck

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.OptionConverters.RichOptional

/** Offboard a sequencer from the current global domain topology state.
  * The offboarding happens if the sequencer is present in the topology state but not in DsoRules.
  * We deliberately do not go through off-boarded sv since contrary to the party the sequencer id is mutable
  * so for a single SV we would then need to track a list of sequencer ids that have been used in the past which gets quite messy.
  * Relying on the diff between topology state and DsoRules does allow for a race:
  * An SV might have seen an updated topology state but not yet the updated DsoRules. However for the topology state to be updated a threshold of SVs must have seen the DsoRules change first so the offboarding proposal will just die.
  */
class SvOffboardingSequencerTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    val participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SequencerId]
    with SyncConnectionStalenessCheck {

  // TODO(tech-debt): this is an almost exact copy of SvOffboardingMediatorTrigger => share the code to avoid missed bugfixes
  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SequencerId]] = {
    for {
      rulesAndStates <- dsoStore.getDsoRulesWithSvNodeStates()
      currentSequencerState <- participantAdminConnection.getSequencerSynchronizerState(
        rulesAndStates.dsoRules.domain,
        AuthorizedState,
      )
    } yield {
      val dsoRulesCurrentSequencers = getSequencerIds(
        rulesAndStates.svNodeStates.values.flatMap(
          _.payload.state.synchronizerNodes.values().asScala
        )
      )
      if (dsoRulesCurrentSequencers.isEmpty) {
        // Prudent engineering: always keep at least one sequencer active
        logger.warn(
          show"Not reconciling with target state that would leave no active sequencers: $rulesAndStates"
        )
        Seq.empty
      } else {
        val sequencersToRemove =
          currentSequencerState.mapping.active
            .filterNot(e => dsoRulesCurrentSequencers.contains(e))
        if (sequencersToRemove.nonEmpty)
          logger.info {
            import com.digitalasset.canton.util.ShowUtil.showPretty
            show"Planning to remove sequencers $sequencersToRemove to match $rulesAndStates"
          }
        sequencersToRemove
      }
    }
  }

  override protected def completeTask(task: SequencerId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    // TODO(tech-debt): pass through the domain id from the task, and double-check task completion for races
    for {
      dsoRules <- dsoStore.getDsoRules()
      _ <- participantAdminConnection.ensureSequencerSynchronizerStateRemoval(
        dsoRules.domain,
        task,
        RetryFor.Automation,
      )
    } yield {
      TaskSuccess(show"Removed sequencer $task from domain ${dsoRules.domain}")
    }
  }

  override protected def isStaleTask(task: SequencerId)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      // TODO(tech-debt): pass through the domain id from the task, and double-check staleness check for races
      dsoRules <- dsoStore.getDsoRules()
      sequencerSyncState <- participantAdminConnection.getSequencerSynchronizerState(
        dsoRules.domain,
        AuthorizedState,
      )
      notConnected <- isNotConnectedToSync()
    } yield {
      !sequencerSyncState.mapping.active.contains(task) || notConnected
    }
  }

  private def getSequencerIds(
      members: Iterable[SynchronizerNodeConfig]
  )(implicit tc: TraceContext) = {
    members
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
      .toSeq
  }
}
