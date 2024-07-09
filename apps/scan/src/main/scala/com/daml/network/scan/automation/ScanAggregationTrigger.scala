// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.automation

import com.daml.network.automation.PollingTrigger
import com.daml.network.automation.TriggerContext
import com.daml.network.scan.store.ScanStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import scala.concurrent.{ExecutionContext, Future}

// Trigger that aggregates totals per closed round.
class ScanAggregationTrigger(
    store: ScanStore,
    override protected val context: TriggerContext,
)(implicit val ec: ExecutionContext, val tracer: Tracer)
    extends PollingTrigger {
  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    store.aggregate().map(_ => false)
  }
}
