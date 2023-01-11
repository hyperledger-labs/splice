package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

class InMemoryDomainStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends DomainStore
    with NamedLogging {
  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var stateVar: InMemoryDomainStore.State = InMemoryDomainStore.State(
    Map.empty,
    Promise(),
  )

  override def listConnectedDomains(): Future[Map[DomainAlias, DomainId]] =
    Future.successful(stateVar.connectedDomains)

  override def getDomainId(alias: DomainAlias): Future[DomainId] =
    stateVar.connectedDomains
      .get(alias)
      .fold[Future[DomainId]](
        Future.failed(
          new StatusRuntimeException(
            Status.NOT_FOUND.withDescription(s"Domain alias $alias not found")
          )
        )
      )(Future.successful(_))

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
    override def ingestConnectedDomains(
        domains: Map[DomainAlias, DomainId]
    )(implicit traceContext: TraceContext): Future[Unit] =
      updateState(
        _.setDomains(domains)
      ).map { case (summary, stateChanged) =>
        logger.debug(show"Ingested domain update $summary")
        stateChanged.success(())
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
  ) {
    def setDomains(
        newDomains: Map[DomainAlias, DomainId]
    ): (State, (IngestionSummary, Promise[Unit])) = {
      val summary = summarizeChanges(connectedDomains, newDomains)
      (
        State(
          newDomains,
          Promise(),
        ),
        (summary, stateChanged),
      )
    }
  }

  private case class IngestionSummary(
      addedDomains: Map[DomainAlias, DomainId],
      removedDomains: Map[DomainAlias, DomainId],
      newNumConnectedDomains: Int,
  ) extends PrettyPrinting {
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
