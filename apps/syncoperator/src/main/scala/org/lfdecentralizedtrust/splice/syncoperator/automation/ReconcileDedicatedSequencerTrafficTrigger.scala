// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.automation

import cats.syntax.apply.*
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveLong}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  ReconcileSequencerLimitWithMemberTrafficTriggerBase,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNodeService
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.syncoperator.SyncOperatorSynchronizerNode
import org.lfdecentralizedtrust.splice.syncoperator.automation.ReconcileDedicatedSequencerTrafficTrigger.Task
import org.lfdecentralizedtrust.splice.syncoperator.store.SyncOperatorStore

import scala.concurrent.{ExecutionContext, Future}

/** Keeps the traffic limit of every participant on this operator's sequencer at the traffic
  * purchased for it on this synchronizer, plus the outage traffic allowance while that is set.
  *
  * Each poll reads the traffic state of every member the sequencer knows and the purchased total of
  * every member, so a participant that joins later, also during an outage, gets its limit on the
  * next poll. A participant with no purchase on record has bought nothing, so it gets the allowance
  * alone. Mediators are left to [[MediatorUnlimitedTrafficTrigger]], and sequencers are not charged
  * traffic.
  *
  * Limits are set exactly, both ways: setting the allowance raises them, and removing it lowers
  * them back to the purchased totals, so a participant that used the allowance is below zero until
  * its purchases cover what it consumed.
  *
  * Reading every member on each poll is fine for the few members of a dedicated synchronizer, but
  * would not scale to the global synchronizer.
  *
  * On a synchronizer whose sequencers several operators run, a limit changes only once the
  * sequencer group's threshold of its sequencers have sent the same one, so the operators have to
  * set and remove the allowance together. Whenever their targets differ, as while only some have
  * the allowance set or while one operator's store has not ingested a purchase yet, limits only
  * stay stable if that threshold is above half the active sequencers: with a lower one, the
  * sequencers on each side can reach it and override each other's limits on every poll. Above
  * half, a split where neither side reaches it leaves the limits where they are until enough of
  * them send the same limit.
  */
class ReconcileDedicatedSequencerTrafficTrigger(
    override protected val context: TriggerContext,
    store: SyncOperatorStore,
    synchronizerNodeService: SynchronizerNodeService[SyncOperatorSynchronizerNode],
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration,
    outageTrafficAllowance: Option[PositiveLong],
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task] {

  private val allowance = outageTrafficAllowance.map(_.value)

  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] =
    for {
      connection <- synchronizerNodeService.sequencerAdminConnection()
      (states, totals) <- (
        // An empty filter lists every member the sequencer knows.
        connection.listSequencerTrafficControlState(),
        store.listTotalPurchasedMemberTraffic(),
      ).tupled
    } yield ReconcileDedicatedSequencerTrafficTrigger.tasks(
      states.map(state => state.member -> state.extraTrafficLimit.value).toMap,
      totals,
      allowance,
    )

  override protected def completeTask(task: Task)(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      connection <- synchronizerNodeService.sequencerAdminConnection()
      // Read again, so the grant carries the serial the sequencer expects next.
      (trafficState, sequencerState) <- (
        connection.getSequencerTrafficControlState(task.member),
        connection.getSequencerSynchronizerState(TopologySnapshot.Effective),
      ).tupled
      // And the total after it: a purchase granted since the task was retrieved is then either in
      // the total, or its grant moved the serial and this one is refused and retried.
      current <- currentTask(task)
      limit = trafficState.extraTrafficLimit.value
      _ <- connection.setSequencerTrafficControlState(
        trafficState,
        sequencerState,
        NonNegativeLong.tryCreate(current.target),
        context.pollingClock,
        trafficBalanceReconciliationDelay,
      )
    } yield TaskSuccess(s"Set the traffic limit of ${task.member} from $limit to ${current.target}")

  override protected def isStaleTask(task: Task)(implicit tc: TraceContext): Future[Boolean] =
    for {
      connection <- synchronizerNodeService.sequencerAdminConnection()
      state <- connection.lookupSequencerTrafficControlState(task.member)
      current <- currentTask(task)
    } yield state.forall(state => current.isDone(state.extraTrafficLimit.value))

  // The task with the member's purchased total as it is now, so a purchase ingested since the task
  // was retrieved is not taken back.
  private def currentTask(task: Task)(implicit tc: TraceContext): Future[Task] =
    store.getTotalPurchasedMemberTraffic(task.member).map(total => task.copy(total = total))
}

object ReconcileDedicatedSequencerTrafficTrigger {

  /** The grants that bring each participant on the sequencer to its target, in a fixed order.
    *
    * @param limits
    *   the current limit of every member the sequencer has a traffic state for
    * @param totals
    *   the purchased total of every member with a purchase on record for this synchronizer. A
    *   purchase for a member without a traffic state waits until the sequencer has one.
    */
  def tasks(
      limits: Map[Member, Long],
      totals: Map[Member, Long],
      allowance: Option[Long],
  ): Seq[Task] =
    limits.toSeq
      .filter { case (member, _) =>
        ReconcileSequencerLimitWithMemberTrafficTriggerBase.reconcilesPurchasedTraffic(member)
      }
      .sortBy { case (member, _) => member.toProtoPrimitive }
      .flatMap { case (member, limit) =>
        val task = Task(member, totals.getOrElse(member, 0L), allowance)
        Option.when(!task.isDone(limit))(task)
      }

  final case class Task(member: Member, total: Long, allowance: Option[Long])
      extends PrettyPrinting {

    /** The purchased total, plus the allowance while it is set, capped at the largest limit there
      * is.
      */
    def target: Long =
      allowance.fold(total)(a => if (a > Long.MaxValue - total) Long.MaxValue else total + a)

    /** Whether `limit` leaves nothing to do. Exact both ways, so that removing the allowance lowers
      * a limit it raised.
      */
    def isDone(limit: Long): Boolean = limit == target

    override def pretty: Pretty[Task] = prettyOfClass(
      param("member", _.member),
      param("total", _.total),
      paramIfDefined("allowance", _.allowance),
      param("target", _.target),
    )
  }
}
