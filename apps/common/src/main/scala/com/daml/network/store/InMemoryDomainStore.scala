// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import cats.Show.Shown
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import monocle.macros.syntax.lens.*

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

class InMemoryDomainStore(
    party: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends DomainStore
    with NamedLogging {
  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemoryDomainStore.State = InMemoryDomainStore.State(
    Map.empty,
    Promise(),
    Map.empty,
  )

  override def listConnectedDomains(): Future[Map[DomainAlias, DomainId]] =
    Future.successful(stateVar.connectedDomains)

  override def getDomainId(alias: DomainAlias): Future[DomainId] =
    stateVar.connectedDomains
      .get(alias)
      .fold[Future[DomainId]](
        Future.failed(
          Status.NOT_FOUND.withDescription(s"Domain alias $alias not found").asRuntimeException()
        )
      )(Future.successful(_))

  override def waitForDomainConnection(
      alias: DomainAlias
  )(implicit tc: TraceContext): Future[DomainId] =
    updateState[Future[DomainId]](state =>
      state.connectedDomains.get(alias) match {
        case Some(domainId) => (state, Future.successful(domainId))
        case None =>
          val actionDesc = show"Waiting for domain $alias to be connected"
          logger.info(actionDesc)
          val (newState, promise) = state.addAliasToSignal(alias)
          val connectedOrShutdown = retryProvider
            .waitUnlessShutdown(promise.future)
            .onShutdown {
              val msg =
                show"Gave up waiting for domain $alias to be connected, as we are shutting down"
              logger.debug(msg)
              throw Status.UNAVAILABLE.withDescription(msg).asRuntimeException()
            }
            .map(domainId => {
              logger.info(show"Success: connected to domain $alias with domain-id $domainId")
              domainId
            })
          val supervisedFuture =
            retryProvider.futureSupervisor.supervised(actionDesc)(connectedOrShutdown)
          (newState, supervisedFuture)
      }
    ).flatten

  private def nextDomainStateUpdate[T](
      previousState: InMemoryDomainStore.State
  ): Future[(InMemoryDomainStore.State, Seq[DomainStore.DomainConnectionEvent])] =
    previousState.stateChanged.future.map { _ =>
      val newState = stateVar
      val summary = InMemoryDomainStore.summarizeChanges(
        previousState.connectedDomains,
        newState.connectedDomains,
      )
      val events = summaryToEvents(summary)
      (newState, events)
    }

  private def summaryToEvents(
      summary: InMemoryDomainStore.IngestionSummary
  ): Seq[DomainStore.DomainConnectionEvent] =
    summary.addedDomains.map { case (k, v) =>
      DomainStore.DomainAdded(k, v)
    }.toSeq ++ summary.removedDomains.view.map { case (k, v) =>
      DomainStore.DomainRemoved(k, v)
    }

  def streamEvents(): Source[DomainStore.DomainConnectionEvent, NotUsed] = {
    val initState = stateVar
    val initialEvents =
      summaryToEvents(InMemoryDomainStore.summarizeChanges(Map.empty, initState.connectedDomains))
    Source(initialEvents).concat(
      Source
        .unfoldAsync(initState)(prev => nextDomainStateUpdate(prev).map(Some(_)))
        .mapConcat(identity)
    )
  }

  private def updateState[T](
      f: InMemoryDomainStore.State => (InMemoryDomainStore.State, T)
  ): Future[T] = {
    Future {
      blocking {
        synchronized {
          val (stNew, result) = f(stateVar)
          stateVar = stNew
          result
        }
      }
    }
  }

  val ingestionSink: DomainStore.IngestionSink = new DomainStore.IngestionSink {
    override val ingestionFilter: PartyId = party

    override def ingestConnectedDomains(
        domains: Map[DomainAlias, DomainId]
    )(implicit traceContext: TraceContext): Future[Option[Shown]] =
      updateState(
        _.setDomains(domains)
      ).map { case (summary, stateChanged, aliasSignals) =>
        stateChanged.success(())
        aliasSignals.foreach { case (domainId, promise) =>
          promise.success(domainId)
        }
        Option.when(summary.nonEmpty)(show"$summary")
      }
  }

  override def close(): Unit = ()
}

object InMemoryDomainStore {
  private def summarizeChanges(
      prevState: Map[DomainAlias, DomainId],
      newState: Map[DomainAlias, DomainId],
  ): InMemoryDomainStore.IngestionSummary = {
    val added = newState -- prevState.keySet
    val removed = prevState -- newState.keySet
    InMemoryDomainStore.IngestionSummary(
      added,
      removed,
      newState.size,
    )
  }

  private case class State(
      connectedDomains: Map[DomainAlias, DomainId],
      stateChanged: Promise[Unit],
      domainAliasIngestionsToSignal: Map[DomainAlias, Promise[DomainId]],
  ) {
    def setDomains(
        newDomains: Map[DomainAlias, DomainId]
    ): (
        State,
        (
            IngestionSummary,
            Promise[Unit],
            Iterable[(DomainId, Promise[DomainId])],
        ),
    ) = {
      val summary = summarizeChanges(connectedDomains, newDomains)
      val (readyToSignal, leftoverSignals) =
        domainAliasIngestionsToSignal.partition { case (alias, _) =>
          newDomains.contains(alias)
        }
      val readyToSignalWithDomainId = readyToSignal.view.map { case (alias, promise) =>
        (newDomains(alias), promise)
      }
      (
        State(
          newDomains,
          Promise(),
          leftoverSignals,
        ),
        (summary, stateChanged, readyToSignalWithDomainId),
      )
    }
    def addAliasToSignal(alias: DomainAlias): (State, Promise[DomainId]) = {
      domainAliasIngestionsToSignal.get(alias) match {
        case None =>
          val p = Promise[DomainId]()
          (this.focus(_.domainAliasIngestionsToSignal).modify(_ + (alias -> p)), p)
        case Some(existingP) => (this, existingP)
      }
    }
  }

  private case class IngestionSummary(
      addedDomains: Map[DomainAlias, DomainId],
      removedDomains: Map[DomainAlias, DomainId],
      newNumConnectedDomains: Int,
  ) extends PrettyPrinting {
    def nonEmpty: Boolean =
      addedDomains.nonEmpty || removedDomains.nonEmpty
    override def pretty: Pretty[this.type] = {
      prettyNode(
        "", // intentionally left empty, as that worked better in the log messages above
        param("addedDomains", _.addedDomains),
        param("removedDomains", _.removedDomains),
        param("newNumConnectedDomains", _.newNumConnectedDomains),
      )
    }
  }
}
