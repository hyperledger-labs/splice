package com.daml.network.automation

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.DomainId
import com.daml.network.environment.CoinRetries
import com.daml.network.store.DomainStore
import com.daml.network.util.HasHealth
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.Spanning
import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.control.NonFatal

import DomainOrchestrator.*

/** Orchestrator that spins up exactly one instance of the given service per domain.
  * This can be used to spin up per-domain.
  */
final class DomainOrchestrator private (
    triggerContext: TriggerContext,
    domainStore: DomainStore,
    startService: (DomainStore.DomainAdded, TriggerContext) => Svc,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends SourceBasedTrigger[DomainStore.DomainConnectionEvent]
    with HasHealth
    with FlagCloseableAsync
    with NamedLogging
    with Spanning {

  override protected lazy val context: TriggerContext = triggerContext.copy(
    config = triggerContext.config.copy(
      parallelism = 1
    )
  )

  @volatile
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private[this] var servicesVar: Map[DomainId, (TriggerContext, Svc)] = Map.empty

  // Note that we close the retry providers at the beginning of initiating shutdown to quickly signal shutdown,
  // while we close the services one after the other on .close()
  private def closeRetryProviders(): Unit =
    Lifecycle.close(servicesVar.values.map(_._1.retryProvider).toSeq *)(logger)

  private def closeServices(): Unit = Lifecycle.close(servicesVar.values.map(_._2).toSeq *)(logger)

  triggerContext.retryProvider.runOnShutdown(new RunOnShutdown {
    override def name = s"shutdown per domain retry providers"
    // this is not perfectly precise, but CoinRetries.close is idempotent
    override def done = false
    override def run() = closeRetryProviders()
  })(TraceContext.empty)

  private def updateServices[T](
      f: Map[DomainId, (TriggerContext, Svc)] => (Map[DomainId, (TriggerContext, Svc)], T)
  ): FutureUnlessShutdown[T] =
    FutureUnlessShutdown(Future {
      if (triggerContext.retryProvider.isShuttingDown) {
        // Avoid allocating new services when we are shutting down.
        UnlessShutdown.AbortedDueToShutdown
      } else
        blocking {
          synchronized {
            val (servicesNew, result) = f(servicesVar)
            servicesVar = servicesNew
            // Shutdown might have been initiated concurrently with our change to the service map
            if (triggerContext.retryProvider.isShuttingDown) {
              val msg =
                "Detected race between update of services and shutdown: closing all services again to be on the safe side."
              logger.debug(msg)(TraceContext.empty)
              // This must be within the 'synchronized' block to ensure we are reading the right state of the 'servicesVar'
              closeRetryProviders()
              closeServices()
              UnlessShutdown.AbortedDueToShutdown
            } else {
              UnlessShutdown.Outcome(result)
            }
          }
        }
    })

  override protected val source: Source[DomainStore.DomainConnectionEvent, NotUsed] =
    domainStore.streamEvents()

  override def completeTask(
      task: DomainStore.DomainConnectionEvent
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    task match {
      case event: DomainStore.DomainAdded =>
        updateServices(services =>
          if (services.contains(event.domainId)) {
            val msg =
              show"Received duplicate domain connection for ${event.domainId} without a disconnect in between, ignoring..."
            logger.warn(msg)
            (services, TaskSuccess(msg))
          } else {
            val perDomainLoggerFactory =
              triggerContext.loggerFactory.append("domainId", show"${event.domainId}")
            val perDomainRetries =
              new CoinRetries(perDomainLoggerFactory, triggerContext.retryProvider.timeouts)
            val perDomainContext = triggerContext.copy(
              retryProvider = perDomainRetries,
              loggerFactory = perDomainLoggerFactory,
            )
            val newServices =
              services + (event.domainId -> (perDomainContext, startService(
                event,
                perDomainContext,
              )))
            (newServices, TaskSuccess(show"Started new service for ${event.domainId}"))
          }
        ).onShutdown(
          TaskSuccess(
            show"Skipped or aborted starting a new service for ${event.domainId}, as we are shutting down."
          )
        )
      case event: DomainStore.DomainRemoved =>
        updateServices(services =>
          services.get(event.domainId) match {
            case None =>
              val msg =
                show"Received domain disconnection for ${event.domainId} without a connect before, ignoring..."
              logger.warn(msg)
              (services, TaskSuccess(msg))
            case Some((context, svc)) =>
              context.retryProvider.close()
              svc.close()
              val newServices = services - event.domainId
              (newServices, TaskSuccess(show"Stopped service for ${event.domainId}"))
          }
        ).onShutdown(TaskSuccess(show"Stopped service for ${event.domainId} during shutdown"))
    }

  // We can always start & shutdown services so we don’t need to worry about task staleness here.
  protected def isStaleTask(task: DomainStore.DomainConnectionEvent)(implicit tc: TraceContext) =
    Future.successful(false)

  override def isHealthy: Boolean = servicesVar.values.forall(_._2.isHealthy)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    SyncCloseable("Per-domain services", closeServices()) +: super.closeAsync()
}

object DomainOrchestrator {
  private type Svc = HasHealth & AutoCloseable

  def apply(
      triggerContext: TriggerContext,
      domainStore: DomainStore,
      startService: (DomainStore.DomainAdded, TriggerContext) => Svc,
  )(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer): DomainOrchestrator =
    new DomainOrchestrator(triggerContext, domainStore, startService)

  def multipleServices(
      services: Seq[(DomainStore.DomainAdded, TriggerContext) => Svc],
      loggerFactory: NamedLoggerFactory,
  ): (DomainStore.DomainAdded, TriggerContext) => Svc = { case (event, triggerContext) =>
    val lf = loggerFactory
    new HasHealth with AutoCloseable with NamedLogging {
      private val (started, failure) = mapOrAbort(services)(f =>
        try Right(f(event, triggerContext))
        catch { case NonFatal(t) => Left(t) }
      )
      failure.foreach { err =>
        close()
        throw err
      }

      override def isHealthy = started.forall(_.isHealthy)

      override def close(): Unit = Lifecycle.close(started: _*)(logger)

      protected override def loggerFactory = lf
    }
  }

  import collection.immutable.SeqOps

  private[automation] def mapOrAbort[A, CC[_], Abort, B](
      fa: SeqOps[A, CC, ?]
  )(f: A => Either[Abort, B]): (CC[B], Option[Abort]) = {
    val bccb = fa.iterableFactory.iterableFactory[B].newBuilder
    val abort = fa.iterator.collectFirst(Function unlift { a =>
      f(a).fold(
        Some(_),
        { b =>
          bccb += b
          None
        },
      )
    })
    (bccb.result(), abort)
  }
}
