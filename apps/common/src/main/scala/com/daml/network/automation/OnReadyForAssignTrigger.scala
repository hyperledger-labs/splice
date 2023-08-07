package com.daml.network.automation

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.network.environment.ledger.api.ReassignmentEvent
import com.daml.network.store.{CNNodeAppStore, MultiDomainAcsStore}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

abstract class OnReadyForAssignTrigger(
    store: CNNodeAppStore[_, _]
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
