// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding

import cats.implicits.catsSyntaxTuple2Semigroupal
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SvOnboardingUnlimitedTrafficTrigger.UnlimitedTraffic
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{SynchronizerId, Member}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

/** This trigger currently relies on enough SVs working on the same set traffic balance request around the same time,
  * as the trigger works with limited parallelism.
  *
  * TODO(tech-debt): remove this constraint by ensuring that we regularly submit set-traffic-balance requests for ALL members.
  */
class SvOnboardingUnlimitedTrafficTrigger(
    override protected val context: TriggerContext,
    dsoStore: SvDsoStore,
    sequencerAdminConnectionO: Option[SequencerAdminConnection],
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SvOnboardingUnlimitedTrafficTrigger.Task] {

  import SvOnboardingUnlimitedTrafficTrigger.Task

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = {
    for {
      dsoRulesAndStates <- dsoStore.getDsoRulesWithSvNodeStates()
      amuletRules <- dsoStore.getAmuletRules()
      decentralizedSynchronizerConfig = AmuletConfigSchedule(amuletRules)
        .getConfigAsOf(context.clock.now)
        .decentralizedSynchronizer
      // We assume that we can switch over immediately to the new domain. The one case where this might break
      // is if there is a new SV being onboarded just around the time of the domain migration. This seems
      // like an acceptable limitation.
      activeSynchronizerId = SynchronizerId.tryFromString(
        decentralizedSynchronizerConfig.activeSynchronizer
      )
      sequencerAdminConnection = SvUtil.getSequencerAdminConnection(
        sequencerAdminConnectionO
      )
      svMembersWithTrafficState <- MonadUtil
        .sequentialTraverse(
          dsoRulesAndStates
            .activeSvParticipantAndMediatorIds(activeSynchronizerId)
        ) { memberId =>
          for {
            stateO <- sequencerAdminConnection.lookupSequencerTrafficControlState(memberId)
          } yield {
            if (stateO.isEmpty) {
              // This can happen for mediators which are registered in DsoRules before they connect.
              logger.info(s"Member $memberId does not yet have a traffic state, skipping")
            }
            stateO.map(memberId -> _)
          }
        }
        .map(_.flatten)
    } yield {
      // Sorting here so we have a better chance of all SVs working on the same set traffic balance request around the same time.
      svMembersWithTrafficState.sortBy(_._1).collect {
        case (memberId, trafficState) if trafficState.extraTrafficLimit != UnlimitedTraffic =>
          Task(activeSynchronizerId, memberId)
      }
    }
  }

  override protected def completeTask(task: SvOnboardingUnlimitedTrafficTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val sequencerAdminConnection = SvUtil.getSequencerAdminConnection(
      sequencerAdminConnectionO
    )
    for {
      // We must read the state here again to pick up on new serials
      (trafficState, sequencerState) <- (
        sequencerAdminConnection.getSequencerTrafficControlState(task.memberId),
        sequencerAdminConnection.getSequencerSynchronizerState(),
      ).tupled
      _ <- sequencerAdminConnection.setSequencerTrafficControlState(
        trafficState,
        sequencerState,
        UnlimitedTraffic,
        context.pollingClock,
        trafficBalanceReconciliationDelay,
      )
    } yield TaskSuccess(
      s"Updated traffic limit for ${task.memberId} to NonNegativeLong.maxValue"
    )
  }

  override protected def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    val sequencerAdminConnection = SvUtil.getSequencerAdminConnection(
      sequencerAdminConnectionO
    )
    for {
      dsoRulesAndStates <- dsoStore.getDsoRulesWithSvNodeStates()
      trafficState <- sequencerAdminConnection.getSequencerTrafficControlState(task.memberId)
    } yield {
      !dsoRulesAndStates
        .activeSvParticipantAndMediatorIds(task.synchronizerId)
        .contains(task.memberId) || trafficState.extraTrafficLimit == UnlimitedTraffic
    }
  }
}

object SvOnboardingUnlimitedTrafficTrigger {

  val UnlimitedTraffic: NonNegativeLong = NonNegativeLong.maxValue

  final case class Task(
      synchronizerId: SynchronizerId,
      memberId: Member,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("synchronizerId", _.synchronizerId),
        param("memberId", _.memberId),
      )
  }
}
