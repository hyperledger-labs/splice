// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.topology.{Member, ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.store.AppStore
import org.lfdecentralizedtrust.splice.util.AssignedContract

import scala.concurrent.{ExecutionContext, Future}

/** Grants the traffic purchased via `MemberTraffic` contracts on the sequencer this trigger's
  * connection serves, skipping contracts that name another synchronizer. Only the SV app's
  * `ReconcileSequencerLimitWithMemberTrafficTrigger` extends it: each SV runs one, which grants on
  * that SV's own sequencer of the decentralized synchronizer.
  *
  * This trigger currently relies on enough SVs working on the same set traffic balance request
  * around the same time. It also depends on the sorting of tasks done in OnAssignedContractTrigger
  * to make this more likely to succeed.
  *
  * TODO(tech-debt): remove this constraint by ensuring that we regularly submit set-traffic-balance
  * requests for ALL members.
  */
abstract class ReconcileSequencerLimitWithMemberTrafficTriggerBase(
    store: AppStore,
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      splice.decentralizedsynchronizer.MemberTraffic.ContractId,
      splice.decentralizedsynchronizer.MemberTraffic,
    ](
      store,
      splice.decentralizedsynchronizer.MemberTraffic.COMPANION,
    ) {

  /** Admin connection to the sequencer this trigger grants on. */
  protected def sequencerAdminConnection()(implicit
      tc: TraceContext
  ): Future[SequencerAdminConnection]

  /** Total traffic purchased for `memberId` on the synchronizer this trigger grants on. */
  protected def getTotalPurchasedMemberTraffic(memberId: Member)(implicit
      tc: TraceContext
  ): Future[Long]

  /** Traffic already consumed by `memberId` before this trigger took over, added to the purchased
    * total. A `Left` skips the member and carries the reason.
    */
  protected def trafficLimitOffset(memberId: Member)(implicit
      tc: TraceContext
  ): Future[Either[String, Long]]

  override def completeTask(
      memberTraffic: AssignedContract[
        splice.decentralizedsynchronizer.MemberTraffic.ContractId,
        splice.decentralizedsynchronizer.MemberTraffic,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Member
      .fromProtoPrimitive_(memberTraffic.payload.memberId)
      .fold(
        err =>
          // Skip contracts with invalid member ids
          Future.successful(TaskSuccess(s"Skipping MemberTraffic with invalid memberId: ${err}")),
        memberId =>
          SynchronizerId
            .fromString(memberTraffic.payload.synchronizerId)
            .fold(
              err => {
                // Skip contracts with invalid synchronizer ids
                logger.warn(s"Skipping MemberTraffic with unparseable synchronizerId: ${err}")
                Future.successful(
                  TaskSuccess(s"Skipping MemberTraffic with invalid synchronizerId: ${err}")
                )
              },
              synchronizerId => processMember(memberId, synchronizerId),
            ),
      )
  }

  private def processMember(memberId: Member, synchronizerId: SynchronizerId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    if (ReconcileSequencerLimitWithMemberTrafficTriggerBase.reconcilesPurchasedTraffic(memberId))
      reconcileMemberOnSequencer(memberId, synchronizerId)
    else
      Future.successful(
        TaskSuccess(
          s"Skipping MemberTraffic contract for $memberId: only participants' purchased traffic " +
            "is reconciled"
        )
      )

  private def reconcileMemberOnSequencer(memberId: Member, synchronizerId: SynchronizerId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    sequencerAdminConnection().flatMap { connection =>
      connection.getStatus.map(_.successOption.map(_.synchronizerId.logical)).flatMap {
        case None =>
          Future.failed(
            Status.FAILED_PRECONDITION
              .withDescription("Sequencer is not yet initialized")
              .asRuntimeException()
          )
        case Some(served) if served != synchronizerId =>
          // Traffic can be purchased for any registered synchronizer, so we observe contracts this
          // sequencer does not serve. They are granted by the operator of the synchronizer they
          // name, on that synchronizer's own sequencer.
          Future.successful(
            TaskSuccess(
              s"Skipping MemberTraffic contract for synchronizer $synchronizerId, " +
                s"this sequencer serves $served"
            )
          )
        case Some(_) =>
          trafficLimitOffset(memberId).flatMap {
            case Left(reason) =>
              Future.successful(
                TaskSuccess(s"Skipping MemberTraffic contract for $memberId: $reason")
              )
            case Right(offset) =>
              reconcileExtraTrafficLimitForMember(memberId, offset, connection)
          }
      }
    }

  private def reconcileExtraTrafficLimitForMember(
      memberId: Member,
      offset: Long,
      sequencerAdminConnection: SequencerAdminConnection,
  )(implicit tc: TraceContext): Future[TaskSuccess] = {
    sequencerAdminConnection.lookupSequencerTrafficControlState(memberId).flatMap {
      case None =>
        Future.successful(
          TaskSuccess(
            s"No traffic state found for member $memberId. It is likely that the member has been disabled as it was lagging behind and prevented sequencer pruning."
          )
        )
      case Some(trafficState) =>
        for {
          // Compute new extra traffic limit
          totalPurchasedTraffic <- getTotalPurchasedMemberTraffic(memberId)
          newExtraTrafficLimit = NonNegativeLong
            .tryCreate(offset + totalPurchasedTraffic)

          // Get current effective sequencer domain state
          sequencerSynchronizerState <- sequencerAdminConnection
            .getSequencerSynchronizerState(topologySnapshot = TopologySnapshot.Effective)
          currentExtraTrafficLimit = trafficState.extraTrafficLimit

          // Compare and reconcile old and new limits
          taskOutcome <-
            if (currentExtraTrafficLimit < newExtraTrafficLimit) {
              sequencerAdminConnection
                .setSequencerTrafficControlState(
                  trafficState,
                  sequencerSynchronizerState,
                  newExtraTrafficLimit,
                  context.pollingClock,
                  trafficBalanceReconciliationDelay,
                )
                .map(_ =>
                  TaskSuccess(
                    s"Updated extra traffic limit for member ${memberId} from ${currentExtraTrafficLimit} to ${newExtraTrafficLimit}"
                  )
                )
            } else {
              Future.successful(
                TaskSuccess(
                  s"Skipping since traffic limit is already up to date (previous limit = ${currentExtraTrafficLimit}, new limit = ${newExtraTrafficLimit})."
                )
              )
            }
        } yield taskOutcome
    }
  }
}

object ReconcileSequencerLimitWithMemberTrafficTriggerBase {

  /** Whether the traffic purchased for `member` is reconciled with its limit. Only for participants:
    * a mediator has no purchase path and is granted unlimited traffic instead, which a purchase
    * someone made for it must not lower, and a sequencer is not charged traffic.
    */
  def reconcilesPurchasedTraffic(member: Member): Boolean = member match {
    case _: ParticipantId => true
    case _ => false
  }
}
