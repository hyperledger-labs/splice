package com.daml.network.automation

import com.daml.network.config.AutomationConfig
import com.daml.network.environment.RetryProvider
import com.daml.network.util.HasHealth
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.Spanning

import java.util.concurrent.atomic.AtomicReference

/** Shared base class for running ingestion and task-handler automation in applications. */
abstract class AutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    retryProvider: RetryProvider,
) extends HasHealth
    with FlagCloseableAsync
    with NamedLogging
    with Spanning {

  private[this] val backgroundServices: AtomicReference[Seq[HasHealth & AutoCloseable]] =
    new AtomicReference(
      Seq.empty
    )

  /** Shared parameters for instantiating triggers. */
  protected def triggerContext: TriggerContext =
    TriggerContext(automationConfig, timeouts, clock, retryProvider, loggerFactory)

  override def isHealthy: Boolean = backgroundServices.get().forall(_.isHealthy)

  /** Register a background service orchestrated by and required for this automation service.
    *
    * The background service is promptly closed when the automation service is closed.
    */
  final protected def registerService(service: HasHealth & AutoCloseable): Unit = {
    val _ = backgroundServices.getAndUpdate(_.prepended(service))
    ()
  }

  private[this] val resources: AtomicReference[Seq[AutoCloseable]] = new AtomicReference(Seq.empty)

  /** Register a resource that should be promptly closed when closing the automation service. */
  final protected def registerResource[T <: AutoCloseable](resource: T): T = {
    val _ = resources.getAndUpdate(_.prepended(resource))
    resource
  }

  final protected def registerTrigger(trigger: Trigger): Unit = {
    registerService(trigger)
    trigger.run()
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      SyncCloseable(
        "Orchestrated services",
        Lifecycle.close(backgroundServices.get(): _*)(logger),
      ),
      SyncCloseable(
        "Managed resources",
        Lifecycle.close(resources.get(): _*)(logger),
      ),
    )
}
