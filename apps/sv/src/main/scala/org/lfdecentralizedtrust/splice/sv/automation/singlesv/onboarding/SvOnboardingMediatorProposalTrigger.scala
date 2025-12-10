// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding

import cats.implicits.{catsSyntaxTuple2Semigroupal, showInterpolator}
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SvOnboardingMediatorProposalTrigger.MediatorToOnboard
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.MemberIdUtil
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{MediatorId, SequencerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SyncConnectionStalenessCheck

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional

/** Onboards a new mediator to the current global domain topology state.
  * The onboarding only happens if the following conditions are met:
  * - the mediator is configured in the domain config found in the DsoRules
  * - the sequencer configured alongside the mediator is already part of the topology state.
  *      This is required for the mediator to be able to read from an existing sequencer counter.
  *      This also requires that the sequencer is bootstrapped at exactly the topology transaction that added it.
  * - to speed up our onboarding, as we know the sequencer and mediator get added to the dso rules at the same time, but the sequencer will
  *   be the first to be added to the topology state, we start the task without waiting for the sequencer to be added to the topology state,
  *   and wait for the sequencer in the task run itself
  */
class SvOnboardingMediatorProposalTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    val participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[MediatorToOnboard]
    with SyncConnectionStalenessCheck {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[MediatorToOnboard]] = {
    for {
      rulesAndStates <- dsoStore.getDsoRulesWithSvNodeStates()
      synchronizerId = rulesAndStates.dsoRules.domain
      currentMediatorState <- participantAdminConnection.getMediatorSynchronizerState(
        synchronizerId,
        AuthorizedState,
      )
    } yield {
      val currentSynchronizerConfigs = rulesAndStates.currentSynchronizerNodeConfigs()
      val synchronizerNodeConfiguredNodes = currentSynchronizerConfigs
        .flatMap(domainConfigs =>
          (domainConfigs.mediator.toScala -> domainConfigs.sequencer.toScala).tupled
        )
        .map { case (mediatorConfig, sequencerConfig) =>
          val mediatorId = mediatorConfig.mediatorId
          MemberIdUtil.MediatorId
            .tryFromProtoPrimitive(mediatorId, "mediatorId") -> MemberIdUtil.SequencerId
            .tryFromProtoPrimitive(sequencerConfig.sequencerId, "sequencerId")
        }
      val mediatorsToAdd =
        synchronizerNodeConfiguredNodes
          .filterNot { case (mediatorId, _) =>
            currentMediatorState.mapping.active.contains(mediatorId)
          }
          .map { case (mediatorId, sequencerId) =>
            MediatorToOnboard(
              synchronizerId,
              mediatorId,
              sequencerId,
            )
          }

      if (mediatorsToAdd.nonEmpty)
        logger.info {
          import com.digitalasset.canton.util.ShowUtil.showPretty
          show"Planning to add mediators $mediatorsToAdd to match $rulesAndStates"
        }
      mediatorsToAdd
    }
  }

  override protected def completeTask(task: MediatorToOnboard)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    logger.info(show"Adding mediator $task")
    for {
      _ <- context.retryProvider.waitUntil(
        RetryFor.Automation,
        "sequencer_added_to_topology_state",
        s"Sequencer is added to the topology state for $task",
        participantAdminConnection
          .getSequencerSynchronizerState(task.synchronizerId, AuthorizedState)
          .map(state =>
            // required so that the mediator doesn't have an assigned counter when the sequencer initializes from the snapshot
            // if the mediator would have a counter, it will not be able to initialize from the sequencer
            if (!state.mapping.active.contains(task.sequencerId))
              throw Status.FAILED_PRECONDITION
                .withDescription(s"Sequencer not yet observed for task $task")
                .asRuntimeException()
          ),
        logger,
      )
      _ <- participantAdminConnection.ensureMediatorSynchronizerStateAdditionProposal(
        task.synchronizerId,
        task.mediatorId,
        RetryFor.Automation,
      )
    } yield {
      TaskSuccess(show"Added mediator $task")
    }
  }

  override protected def isStaleTask(task: MediatorToOnboard)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      notConnected <- isNotConnectedToSync()
      state <- participantAdminConnection.getMediatorSynchronizerState(
        task.synchronizerId,
        AuthorizedState,
      )
    } yield {
      state.mapping.active.contains(task.mediatorId) || notConnected
    }
  }
}

object SvOnboardingMediatorProposalTrigger {

  case class MediatorToOnboard(
      synchronizerId: SynchronizerId,
      mediatorId: MediatorId,
      sequencerId: SequencerId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[MediatorToOnboard.this.type] = prettyOfClass(
      param("synchronizerId", _.synchronizerId),
      param("mediatorId", _.mediatorId),
      param("sequencerId", _.sequencerId),
    )
  }

}
