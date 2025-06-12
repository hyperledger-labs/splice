// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.topology.{SynchronizerId, Member}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** This trigger currently relies on enough SVs working on the same set traffic balance request around the same time.
  * It also depends on the sorting of tasks done in OnAssignedContractTrigger to make this more likely to succeed.
  *
  * TODO(tech-debt): remove this constraint by ensuring that we regularly submit set-traffic-balance requests for ALL members.
  */
class ReconcileSequencerLimitWithMemberTrafficTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    sequencerAdminConnectionO: Option[SequencerAdminConnection],
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

  override def completeTask(
      memberTraffic: AssignedContract[
        splice.decentralizedsynchronizer.MemberTraffic.ContractId,
        splice.decentralizedsynchronizer.MemberTraffic,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Member
      .fromProtoPrimitive_(memberTraffic.payload.memberId)
      .fold(
        err => {
          // Skip contracts with invalid member ids
          Future.successful(TaskSuccess(s"Skipping MemberTraffic with invalid memberId: ${err}"))
        },
        memberId => {
          val synchronizerId = SynchronizerId.tryFromString(memberTraffic.payload.synchronizerId)
          val sequencerAdminConnection = SvUtil.getSequencerAdminConnection(
            sequencerAdminConnectionO
          )
          sequencerAdminConnection.getStatus
            .map(_.successOption.map(_.synchronizerId))
            .flatMap {
              case None =>
                Future.failed(
                  Status.FAILED_PRECONDITION
                    .withDescription("Sequencer is not yet initialized")
                    .asRuntimeException()
                )
              case Some(sequencerSynchronizerId)
                  if sequencerSynchronizerId.logical != synchronizerId =>
                Future.failed(
                  Status.INTERNAL
                    .withDescription(
                      s"The MemberTraffic contract synchronizerId must match the connected domain ${sequencerSynchronizerId}"
                    )
                    .asRuntimeException()
                )
              case _ =>
                store
                  .getDsoRulesWithSvNodeStates()
                  .flatMap(rulesAndStates => {
                    if (
                      rulesAndStates
                        .activeSvParticipantAndMediatorIds(synchronizerId)
                        .contains(memberId)
                    ) {
                      // SVs are granted unlimited traffic and do not need to purchase it via MemberTraffic contracts.
                      // While the top-up trigger for SV validators is disabled by default, we also explicitly ignore
                      // SV related MemberTraffic contracts here as a safeguard for the case of 3rd party top-ups
                      // of SV nodes or an SV validator misconfiguration that changes the defaults.
                      Future
                        .successful(
                          TaskSuccess(s"Skipping MemberTraffic contract for SV node $memberId")
                        )
                    } else {
                      val trafficLimitOffset =
                        rulesAndStates.dsoRules.payload.initialTrafficState.asScala
                          .get(memberId.toProtoPrimitive)
                          .fold(0L)(_.consumedTraffic)
                      reconcileExtraTrafficLimitForMember(
                        memberId,
                        synchronizerId,
                        trafficLimitOffset,
                        sequencerAdminConnection,
                      )
                    }
                  })
            }
        },
      )
  }

  private def reconcileExtraTrafficLimitForMember(
      memberId: Member,
      synchronizerId: SynchronizerId,
      trafficLimitOffset: Long,
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
          totalPurchasedTraffic <- store.getTotalPurchasedMemberTraffic(memberId, synchronizerId)
          newExtraTrafficLimit = NonNegativeLong
            .tryCreate(trafficLimitOffset + totalPurchasedTraffic)

          // Get current sequencer domain state
          sequencerSynchronizerState <- sequencerAdminConnection.getSequencerSynchronizerState()
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
              Future(
                TaskSuccess(
                  s"Skipping since traffic limit is already up to date (previous limit = ${currentExtraTrafficLimit}, new limit = ${newExtraTrafficLimit})."
                )
              )
            }
        } yield taskOutcome
    }
  }
}
