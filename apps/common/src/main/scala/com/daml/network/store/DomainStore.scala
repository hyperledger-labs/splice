package com.daml.network.store

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
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

  /** Return the unique domain id in the store.
    * Fails if there is more than one domain.
    * TODO(M3-18) Remove all usages of this
    */
  def getUniqueDomainId(): Future[DomainId]

  /** Wait for at least one domain to be connected.
    * TODO(M3-18) Either make this wait for specific domains
    * or remove usage.
    */
  def signalWhenConnected(): Future[Unit]

  def signalWhenConnected(alias: DomainAlias): Future[Unit]

  /** Stream all domain connection events, starts with
    * DomainAdded for all current domains and then sends updates.
    * This function can skip domains if they are removed quickly again
    * so you never see the addition or the remove. However, if you do
    * see the addition you will also see a removal if it happens.
    */
  def streamEvents(): Source[DomainStore.DomainConnectionEvent, NotUsed]
}

object DomainStore {
  sealed trait DomainConnectionEvent extends Product with Serializable with PrettyPrinting {}
  final case class DomainAdded(
      domainAlias: DomainAlias,
      domainId: DomainId,
  ) extends DomainConnectionEvent {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domainAlias", _.domainAlias), param("domainId", _.domainId))
  }
  final case class DomainRemoved(
      domainAlias: DomainAlias,
      domainId: DomainId,
  ) extends DomainConnectionEvent {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domainAlias", _.domainAlias), param("domainId", _.domainId))
  }

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
