// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent
import org.lfdecentralizedtrust.splice.store.{AppStore, MultiDomainAcsStore}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

abstract class OnReadyForAssignTrigger(
    store: AppStore
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SourceBasedTrigger[ReassignmentEvent.Unassign] {

  override protected def source(implicit
      traceContext: TraceContext
  ): Source[ReassignmentEvent.Unassign, NotUsed] =
    store.multiDomainAcsStore.streamReadyForAssign()

  override final protected def isStaleTask(
      task: ReassignmentEvent.Unassign
  )(implicit tc: TraceContext): Future[Boolean] = {
    import MultiDomainAcsStore.ReassignmentId
    store.multiDomainAcsStore
      .isReadyForAssign(task.contractId, ReassignmentId.fromUnassign(task))
      .map(!_)
  }

}
