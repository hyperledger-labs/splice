package com.daml.network.automation

import com.daml.network.config.AutomationConfig
import com.daml.network.environment.RetryProvider
import com.daml.network.util.HasHealth
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.tracing.Spanning

import java.util.concurrent.atomic.AtomicReference
import scala.reflect.ClassTag

/** Shared base class for running ingestion and task-handler automation in applications. */
abstract class AutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    override protected[this] val retryProvider: RetryProvider,
) extends HasHealth
    with FlagCloseableAsync
    with RetryProvider.Has
    with NamedLogging
    with Spanning {

  type BackgroundService = HasHealth & AutoCloseable

  private[this] val backgroundServices: AtomicReference[Seq[BackgroundService]] =
    new AtomicReference(
      Seq.empty
    )

  /** Shared parameters for instantiating triggers. */
  protected def triggerContext: TriggerContext =
    TriggerContext(
      automationConfig,
      clock = clock,
      pollingClock = new WallClock(timeouts, loggerFactory),
      retryProvider,
      loggerFactory,
      retryProvider.metricsFactory,
    )

  override def isHealthy: Boolean = backgroundServices.get().forall(_.isHealthy)

  /** Register a background service orchestrated by and required for this automation service.
    *
    * The background service is promptly closed when the automation service is closed.
    */
  final protected def registerService(service: BackgroundService): Unit = {
    val _ = backgroundServices.getAndUpdate(_.prepended(service))
    ()
  }

  final protected def registerTrigger(trigger: Trigger): Unit = {
    registerService(trigger)
    val paused = automationConfig.pausedTriggers.contains(trigger.getClass.getCanonicalName)
    trigger.run(paused)
  }

  /** Returns all triggers of the given class */
  final def triggers[T <: Trigger](implicit tag: ClassTag[T]): Seq[T] = {
    backgroundServices
      .get()
      .collect { case trigger: T =>
        trigger
      }
  }

  /** Returns the trigger of the given class, if there is exactly one. Otherwise, throws an exception. */
  final def trigger[T <: Trigger](implicit tag: ClassTag[T]): T = {
    val matchingTriggers = triggers[T]
    matchingTriggers match {
      case Seq(result) => result
      case _ =>
        throw new RuntimeException(
          s"Expected exactly one trigger of type ${tag.runtimeClass.getCanonicalName}, but there are ${matchingTriggers.length}"
        )
    }

  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      SyncCloseable(
        "Orchestrated services",
        Lifecycle.close(backgroundServices.get(): _*)(logger),
      )
    )
}
