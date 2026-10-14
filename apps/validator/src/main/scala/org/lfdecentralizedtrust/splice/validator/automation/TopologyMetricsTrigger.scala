// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.validator.metrics.TopologyMetrics
import org.lfdecentralizedtrust.splice.validator.store.PartyToParticipantStore

import scala.concurrent.{ExecutionContext, Future}

class TopologyMetricsTrigger(
    override protected val context: TriggerContext,
    store: PartyToParticipantStore,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  private val topologyMetrics = new TopologyMetrics(context.metricsFactory)

  override def performWorkIfAvailable()(implicit
      tc: TraceContext
  ): Future[Boolean] =
    for {
      initialized <- store.lastIngestedOffset().map(_.isDefined)
      hostingParticipants <- store.hostingParticipants()
    } yield {
      if (initialized) updateMetrics(hostingParticipants)
      false
    }

  private def updateMetrics(
      hostingParticipants: Map[PartyId, Seq[ParticipantId]]
  ): Unit = {
    topologyMetrics.numPartiesGauge.updateValue(hostingParticipants.size.toDouble)

    val partiesPerParticipant: Map[ParticipantId, Int] =
      hostingParticipants.values.flatten.groupMapReduce(identity)(_ => 1)(_ + _)
    topologyMetrics.numPartiesPerParticipantGauges.keys.foreach { participantId =>
      if (!partiesPerParticipant.contains(participantId))
        topologyMetrics.getNumPartiesPerParticipantGauge(participantId).updateValue(0.0)
    }
    partiesPerParticipant.foreach { case (participantId, count) =>
      topologyMetrics.getNumPartiesPerParticipantGauge(participantId).updateValue(count.toDouble)
    }
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super
      .closeAsync()
      .appended(
        SyncCloseable(
          "topology metrics",
          topologyMetrics.close(),
        )
      )
}
