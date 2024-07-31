// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice
import com.daml.network.environment.SequencerAdminConnection
import com.daml.network.sv.ExtraSynchronizerNode
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.topology.{DomainId, Member}
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
    extraSynchronizerNodes: Map[String, ExtraSynchronizerNode],
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
          val domainId = DomainId.tryFromString(memberTraffic.payload.synchronizerId)
          val sequencerAdminConnection = SvUtil.getSequencerAdminConnection(
            domainId,
            sequencerAdminConnectionO,
            extraSynchronizerNodes,
          )
          sequencerAdminConnection.getStatus
            .map(_.successOption.map(_.domainId))
            .flatMap {
              case None =>
                Future.failed(
                  Status.FAILED_PRECONDITION
                    .withDescription("Sequencer is not yet initialized")
                    .asRuntimeException()
                )
              case Some(sequencerDomainId) if sequencerDomainId != domainId =>
                Future.failed(
                  Status.INTERNAL
                    .withDescription(
                      s"The MemberTraffic contract domainId must match the connected domain ${sequencerDomainId}"
                    )
                    .asRuntimeException()
                )
              case _ =>
                store
                  .getDsoRulesWithSvNodeStates()
                  .flatMap(rulesAndStates => {
                    if (
                      rulesAndStates.activeSvParticipantAndMediatorIds(domainId).contains(memberId)
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
                        domainId,
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
      domainId: DomainId,
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
          totalPurchasedTraffic <- store.getTotalPurchasedMemberTraffic(memberId, domainId)
          newExtraTrafficLimit = NonNegativeLong
            .tryCreate(trafficLimitOffset + totalPurchasedTraffic)

          // Get current sequencer domain state
          sequencerDomainState <- sequencerAdminConnection.getSequencerDomainState()
          currentExtraTrafficLimit = trafficState.extraTrafficLimit

          // Compare and reconcile old and new limits
          taskOutcome <-
            if (currentExtraTrafficLimit < newExtraTrafficLimit) {
              sequencerAdminConnection
                .setSequencerTrafficControlState(
                  trafficState,
                  sequencerDomainState,
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
