// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding

import cats.implicits.showInterpolator
import com.digitalasset.canton.topology.MediatorId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SyncConnectionStalenessCheck
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.MemberIdUtil

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.OptionConverters.RichOptional

/** Offboard a mediator from the current global domain topology state.
  * The offboarding happens if the mediator is present in the topology state but not in DsoRules.
  * We deliberately do not go through off-boarded sv since contrary to the party the mediator id is mutable
  * so for a single SV we would then need to track a list of mediator ids that have been used in the past which gets quite messy.
  * Relying on the diff between topology state and DsoRules does allow for a race:
  * An SV might have seen an updated topology state but not yet the updated DsoRules. However for the topology state to be updated a threshold of SVs must have seen the DsoRules change first so the offboarding proposal will just die.
  */
class SvOffboardingMediatorTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    val participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[MediatorId]
    with SyncConnectionStalenessCheck {

  // TODO(tech-debt): this is an almost exact copy of SvOffboardingSequencerTrigger => share the code to avoid missed bugfixes

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[MediatorId]] = {
    for {
      rulesAndStates <- dsoStore.getDsoRulesWithSvNodeStates()
      currentMediatorState <- participantAdminConnection.getMediatorSynchronizerState(
        rulesAndStates.dsoRules.domain,
        AuthorizedState,
      )
    } yield {
      val dsoRulesCurrentMediators = getMediatorIds(
        rulesAndStates.svNodeStates.values.flatMap(
          _.payload.state.synchronizerNodes.values().asScala
        )
      )
      if (dsoRulesCurrentMediators.isEmpty) {
        // Prudent engineering: always keep at least one mediator active
        logger.warn(
          show"Not reconciling with target state that would leave no active mediators: $rulesAndStates"
        )
        Seq.empty
      } else {
        val mediatorsToRemove = currentMediatorState.mapping.active
          .filterNot(e => dsoRulesCurrentMediators.contains(e))
        if (mediatorsToRemove.nonEmpty)
          logger.info {
            show"Planning to remove sequencers $mediatorsToRemove to match $rulesAndStates"
          }
        mediatorsToRemove
      }
    }
  }

  override protected def completeTask(task: MediatorId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      dsoRules <- dsoStore.getDsoRules()
      _ <- participantAdminConnection.ensureMediatorSynchronizerStateRemovalProposal(
        dsoRules.domain,
        task,
        RetryFor.Automation,
      )
    } yield {
      TaskSuccess(show"Removed mediator $task from domain ${dsoRules.domain}")
    }
  }

  override protected def isStaleTask(task: MediatorId)(implicit
      tc: TraceContext
  ): Future[Boolean] = isNotConnectedToSync()

  private def getMediatorIds(
      members: Iterable[SynchronizerNodeConfig]
  ) = {
    members
      .flatMap(_.mediator.toScala)
      .map(_.mediatorId)
      .map(mediatorId =>
        MemberIdUtil.MediatorId
          .tryFromProtoPrimitive(mediatorId, "mediatorId")
      )
      .toSeq
  }
}
