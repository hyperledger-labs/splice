package com.daml.network.automation

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.network.environment.ledger.api.TransferEvent
import com.daml.network.store.{CNNodeAppStore, MultiDomainAcsStore}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

abstract class OnReadyForTransferInTrigger(
    store: CNNodeAppStore[_, _]
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SourceBasedTrigger[TransferEvent.Out] {

  override protected def source(implicit
      traceContext: TraceContext
  ): Source[TransferEvent.Out, NotUsed] =
    store.multiDomainAcsStore.streamReadyForTransferIn()

  override final protected def isStaleTask(
      task: TransferEvent.Out
  )(implicit tc: TraceContext): Future[Boolean] = {
    import MultiDomainAcsStore.TransferId
    store.multiDomainAcsStore.isReadyForTransferIn(TransferId.fromTransferOut(task)).map(!_)
  }

}
