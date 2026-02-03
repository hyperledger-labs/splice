// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.Show.Shown
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{SynchronizerId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import monocle.macros.syntax.lens.*

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

class InMemorySynchronizerStore(
    party: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends SynchronizerStore
    with NamedLogging {
  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemorySynchronizerStore.State = InMemorySynchronizerStore.State(
    Map.empty,
    Promise(),
    Map.empty,
  )

  override def listConnectedDomains(): Future[Map[SynchronizerAlias, SynchronizerId]] =
    Future.successful(stateVar.connectedDomains)

  override def getSynchronizerId(alias: SynchronizerAlias): Future[SynchronizerId] =
    stateVar.connectedDomains
      .get(alias)
      .fold[Future[SynchronizerId]](
        Future.failed(
          Status.NOT_FOUND.withDescription(s"Domain alias $alias not found").asRuntimeException()
        )
      )(Future.successful(_))

  override def waitForDomainConnection(
      alias: SynchronizerAlias
  )(implicit tc: TraceContext): Future[SynchronizerId] =
    updateState[Future[SynchronizerId]](state =>
      state.connectedDomains.get(alias) match {
        case Some(synchronizerId) => (state, Future.successful(synchronizerId))
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
            .map(synchronizerId => {
              logger.info(show"Success: connected to domain $alias with domain-id $synchronizerId")
              synchronizerId
            })
          val supervisedFuture =
            retryProvider.futureSupervisor.supervised(actionDesc)(connectedOrShutdown)
          (newState, supervisedFuture)
      }
    ).flatten

  private def nextDomainStateUpdate[T](
      previousState: InMemorySynchronizerStore.State
  ): Future[(InMemorySynchronizerStore.State, Seq[SynchronizerStore.DomainConnectionEvent])] =
    previousState.stateChanged.future.map { _ =>
      val newState = stateVar
      val summary = InMemorySynchronizerStore.summarizeChanges(
        previousState.connectedDomains,
        newState.connectedDomains,
      )
      val events = summaryToEvents(summary)
      (newState, events)
    }

  private def summaryToEvents(
      summary: InMemorySynchronizerStore.IngestionSummary
  ): Seq[SynchronizerStore.DomainConnectionEvent] =
    summary.addedDomains.map { case (k, v) =>
      SynchronizerStore.DomainAdded(k, v)
    }.toSeq ++ summary.removedDomains.view.map { case (k, v) =>
      SynchronizerStore.DomainRemoved(k, v)
    }

  def streamEvents(): Source[SynchronizerStore.DomainConnectionEvent, NotUsed] = {
    val initState = stateVar
    val initialEvents =
      summaryToEvents(
        InMemorySynchronizerStore.summarizeChanges(Map.empty, initState.connectedDomains)
      )
    Source(initialEvents).concat(
      Source
        .unfoldAsync(initState)(prev => nextDomainStateUpdate(prev).map(Some(_)))
        .mapConcat(identity)
    )
  }

  @SuppressWarnings(Array("com.digitalasset.canton.RequireBlocking"))
  private def updateState[T](
      f: InMemorySynchronizerStore.State => (InMemorySynchronizerStore.State, T)
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

  val ingestionSink: SynchronizerStore.IngestionSink = new SynchronizerStore.IngestionSink {
    override val ingestionFilter: PartyId = party

    override def ingestConnectedDomains(
        domains: Map[SynchronizerAlias, SynchronizerId]
    )(implicit traceContext: TraceContext): Future[Option[Shown]] =
      updateState(
        _.setDomains(domains)
      ).map { case (summary, stateChanged, aliasSignals) =>
        stateChanged.success(())
        aliasSignals.foreach { case (synchronizerId, promise) =>
          promise.success(synchronizerId)
        }
        Option.when(summary.nonEmpty)(show"$summary")
      }
  }

  override def close(): Unit = ()
}

object InMemorySynchronizerStore {
  private def summarizeChanges(
      prevState: Map[SynchronizerAlias, SynchronizerId],
      newState: Map[SynchronizerAlias, SynchronizerId],
  ): InMemorySynchronizerStore.IngestionSummary = {
    val added = newState -- prevState.keySet
    val removed = prevState -- newState.keySet
    InMemorySynchronizerStore.IngestionSummary(
      added,
      removed,
      newState.size,
    )
  }

  private case class State(
      connectedDomains: Map[SynchronizerAlias, SynchronizerId],
      stateChanged: Promise[Unit],
      synchronizerAliasIngestionsToSignal: Map[SynchronizerAlias, Promise[SynchronizerId]],
  ) {
    def setDomains(
        newDomains: Map[SynchronizerAlias, SynchronizerId]
    ): (
        State,
        (
            IngestionSummary,
            Promise[Unit],
            Iterable[(SynchronizerId, Promise[SynchronizerId])],
        ),
    ) = {
      val summary = summarizeChanges(connectedDomains, newDomains)
      val (readyToSignal, leftoverSignals) =
        synchronizerAliasIngestionsToSignal.partition { case (alias, _) =>
          newDomains.contains(alias)
        }
      val readyToSignalWithSynchronizerId = readyToSignal.view.map { case (alias, promise) =>
        (newDomains(alias), promise)
      }
      (
        State(
          newDomains,
          Promise(),
          leftoverSignals,
        ),
        (summary, stateChanged, readyToSignalWithSynchronizerId),
      )
    }
    def addAliasToSignal(alias: SynchronizerAlias): (State, Promise[SynchronizerId]) = {
      synchronizerAliasIngestionsToSignal.get(alias) match {
        case None =>
          val p = Promise[SynchronizerId]()
          (this.focus(_.synchronizerAliasIngestionsToSignal).modify(_ + (alias -> p)), p)
        case Some(existingP) => (this, existingP)
      }
    }
  }

  private case class IngestionSummary(
      addedDomains: Map[SynchronizerAlias, SynchronizerId],
      removedDomains: Map[SynchronizerAlias, SynchronizerId],
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
