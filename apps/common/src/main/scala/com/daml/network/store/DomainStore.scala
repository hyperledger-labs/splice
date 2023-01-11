package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

abstract class DomainStore extends AutoCloseable {
  def listConnectedDomains(): Future[Map[DomainAlias, DomainId]]
  def getDomainId(alias: DomainAlias): Future[DomainId]

  /** Stream all domain connection events, starts with
    * DomainAdded for all current domains and then sends updates.
    * This function can skip domains if they are removed quickly again
    * so you never see the addition or the remove. However, if you do
    * see the addition you will also see a removal if it happens.
    */
  def streamEvents(): Source[DomainStore.DomainConnectionEvent, NotUsed]
}

object DomainStore {
  sealed trait DomainConnectionEvent extends Product with Serializable {}
  final case class DomainAdded(
      domainAlias: DomainAlias,
      domainId: DomainId,
  ) extends DomainConnectionEvent
  final case class DomainRemoved(
      domainAlias: DomainAlias,
      domainId: DomainId,
  ) extends DomainConnectionEvent
  trait IngestionSink {

    /** Ingest the set of connected domains. This fully
      * replaces the previously ingested domains.
      */
    def ingestConnectedDomains(domains: Map[DomainAlias, DomainId])(implicit
        traceContext: TraceContext
    ): Future[Unit]
  }

  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): DomainStore =
    storage match {
      case _: MemoryStorage => new InMemoryDomainStore(loggerFactory)(ec)
      // TODO(M3-83) Support persistence (or convince ourselves that this
      // can always be in-memory.
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
