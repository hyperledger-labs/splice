// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.PartyToParticipant
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.validator.metrics.ValidatorAppMetrics

import scala.concurrent.{ExecutionContext, Future}

class TopologyMetricsTrigger(
    override protected val context: TriggerContext,
    metrics: ValidatorAppMetrics,
    scanConnection: ScanConnection,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  override def performWorkIfAvailable()(implicit
      tc: TraceContext
  ): Future[Boolean] =
    for {
      synchronizerId <- scanConnection.getAmuletRulesDomain()(tc)
      partyMappings <- participantAdminConnection.listPartyToParticipant(
        store = Some(TopologyStoreId.SynchronizerStore(synchronizerId))
      )
    } yield {
      updateMetrics(partyMappings)
      false
    }

  private def updateMetrics(
      partyMappings: Seq[TopologyResult[PartyToParticipant]]
  ) = {
    metrics.numberOfPartiesGauge.updateValue(partyMappings.size.toDouble)
  }
}
