// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.Show.Shown
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

abstract class DomainStore extends AutoCloseable {
  def listConnectedDomains(): Future[Map[DomainAlias, DomainId]]
  def getDomainId(alias: DomainAlias): Future[DomainId]

  /** Wait until a domain with the given alias is connected and return its domain-id. */
  def waitForDomainConnection(alias: DomainAlias)(implicit tc: TraceContext): Future[DomainId]

  /** Stream all domain connection events, starts with
    * DomainAdded for all current domains and then sends updates.
    * This function can skip domains if they are removed quickly again
    * so you never see the addition or the remove. However, if you do
    * see the addition you will also see a removal if it happens.
    */
  def streamEvents(): Source[DomainStore.DomainConnectionEvent, NotUsed]

  def ingestionSink: DomainStore.IngestionSink

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

    def ingestionFilter: PartyId

    /** Ingest the set of connected domains. This fully
      * replaces the previously ingested domains.
      *
      * @return a future that completes with a description of the changes done, if there were any
      */
    def ingestConnectedDomains(domains: Map[DomainAlias, DomainId])(implicit
        traceContext: TraceContext
    ): Future[Option[Shown]]
  }
}
