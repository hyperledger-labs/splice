// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.scan.store.historystart.ScanHistoryStart

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

class ScanHistoryStartTrigger(
    historyStart: ScanHistoryStart,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    val mat: Materializer,
) extends PollingTrigger {

  private val recorded = new AtomicBoolean(false)

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    if (recorded.get()) Future.successful(false)
    else
      historyStart.get.map { start =>
        recorded.set(start.isDefined)
        false
      }
}
