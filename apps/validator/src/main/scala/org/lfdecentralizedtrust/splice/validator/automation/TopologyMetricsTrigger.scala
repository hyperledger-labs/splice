// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.PartyToParticipant
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.validator.metrics.TopologyMetrics

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class TopologyMetricsTrigger(
    override protected val context: TriggerContext,
    scanConnection: ScanConnection,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  private val topologyMetrics = new TopologyMetrics(context.metricsFactory)

  override def performWorkIfAvailable()(implicit
      tc: TraceContext
  ): Future[Boolean] =
    for {
      synchronizerId <- scanConnection.getAmuletRulesDomain()(tc)
      partyMappings <-
        participantAdminConnection.listPartyToParticipant(
          store = Some(TopologyStoreId.SynchronizerStore(synchronizerId))
        )
    } yield {
      updateMetrics(partyMappings)
      false
    }

  private def updateMetrics(
      partyMappings: Seq[TopologyResult[PartyToParticipant]]
  ): Unit = {
    // Update total
    topologyMetrics.numPartiesGauge.updateValue(partyMappings.size.toDouble)

    // Update per-participant counts
    val partiesPerParticipant: mutable.Map[ParticipantId, Int] = mutable.Map();
    partyMappings.foreach(result =>
      result.mapping.participants.foreach(hostingInfo =>
        partiesPerParticipant.updateWith(hostingInfo.participantId)(oldValue =>
          Some(oldValue.getOrElse(0) + 1)
        )
      )
    )
    partiesPerParticipant.foreach { case (participantId, count) =>
      val gauge = topologyMetrics.getNumPartiesPerParticipantGauge(participantId)
      gauge.updateValue(count.toDouble)
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
