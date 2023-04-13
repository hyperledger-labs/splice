package com.daml.network.automation

import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.LedgerOffset
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.{DomainStore, OffsetStore}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

class OffsetIngestionService(
    ingestionSink: OffsetStore.IngestionSink,
    domainStore: DomainStore,
    connection: CNLedgerConnection,
    override protected val context: TriggerContext,
)(implicit val ec: ExecutionContext, val tracer: Tracer)
    extends PollingTrigger {

  override def isHealthy = lastQueryFailed.get.isEmpty && super.isHealthy

  val lastQueryFailed: AtomicReference[Option[Throwable]] = new AtomicReference(None)

  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    (for {
      // TODO(#4024) Switch to participant ledger end once it advances on transfers
      domains <- domainStore.listConnectedDomains()
      offsets <- domains.values.toList.traverse(domainId => connection.ledgerEnd(domainId))
      max = offsets
        .collect { case abs: LedgerOffset.Absolute =>
          abs
        }
        .maxByOption(_.getOffset)
      _ <- max.fold(Future.unit)(ingestionSink.ingestOffset(_))
    } yield false).andThen { r =>
      lastQueryFailed.set(r.failed.toOption)
    }
}
