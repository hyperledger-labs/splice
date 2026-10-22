// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.{Member, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ReconcileSequencerLimitWithMemberTrafficTriggerBase,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.{
  SequencerAdminConnection,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Reconciles the traffic purchased on the decentralized synchronizer with this SV's sequencer.
  * Purchases for other synchronizers are granted by their own operators, so they are skipped here.
  */
class ReconcileSequencerLimitWithMemberTrafficTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    synchronizerNodeService: SynchronizerNodeService[LocalSynchronizerNode],
    synchronizerId: SynchronizerId,
    trafficBalanceReconciliationDelay: NonNegativeFiniteDuration,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ReconcileSequencerLimitWithMemberTrafficTriggerBase(
      store,
      trafficBalanceReconciliationDelay,
    ) {

  override protected def sequencerAdminConnection()(implicit
      tc: TraceContext
  ): Future[SequencerAdminConnection] =
    synchronizerNodeService.sequencerAdminConnection()

  override protected def getTotalPurchasedMemberTraffic(memberId: Member)(implicit
      tc: TraceContext
  ): Future[Long] =
    store.getTotalPurchasedMemberTraffic(StoreMemberId(memberId), synchronizerId)

  override protected def trafficLimitOffset(memberId: Member)(implicit
      tc: TraceContext
  ): Future[Either[String, Long]] =
    store.getDsoRulesWithSvNodeStates().map { rulesAndStates =>
      if (rulesAndStates.activeSvParticipantAndMediatorIds(synchronizerId).contains(memberId)) {
        // SVs are granted unlimited traffic and do not need to purchase it via MemberTraffic
        // contracts. While the top-up trigger for SV validators is disabled by default, we also
        // explicitly ignore SV related MemberTraffic contracts here as a safeguard for the case of
        // 3rd party top-ups of SV nodes or an SV validator misconfiguration that changes the
        // defaults.
        Left("it is an SV node, which is granted unlimited traffic")
      } else {
        Right(
          rulesAndStates.dsoRules.payload.initialTrafficState.asScala
            .get(memberId.toProtoPrimitive)
            .fold(0L)(_.consumedTraffic)
        )
      }
    }
}
