// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import cats.syntax.foldable.*
import cats.instances.seq.*
import cats.instances.set.*
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.util.HasHealth
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}

import java.util.concurrent.atomic.AtomicReference
import scala.reflect.ClassTag

/** Shared base class for running ingestion and task-handler automation in applications. */
abstract class AutomationService(
    protected val automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    override protected[this] val retryProvider: RetryProvider,
) extends HasHealth
    with FlagCloseableAsync
    with RetryProvider.Has
    with NamedLogging
    with Spanning {
  import AutomationService.*

  type BackgroundService = HasHealth & AutoCloseable

  def companion: AutomationServiceCompanion

  private val backgroundServices: AtomicReference[Seq[BackgroundService]] =
    new AtomicReference(
      Seq.empty
    )

  /** Shared parameters for instantiating triggers. */
  protected def triggerContext: TriggerContext =
    TriggerContext(
      automationConfig,
      clock = clock,
      pollingClock = new WallClock(timeouts, loggerFactory),
      triggerEnabledSync = TriggerEnabledSynchronization.fromDomainTimeAndParams(
        domainTimeSync,
        domainUnpausedSync,
      ),
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
    val triggerName = identifyTriggerByName(trigger)
    if (companion.expectedTriggers.nonEmpty && !companion.expectedTriggers(triggerName))
      logger.warn(
        s"Registering unexpected trigger $triggerName; possibly missing from $companion's expectedTriggerClasses"
      )(TraceContext.empty)
    val paused = automationConfig.pausedTriggers contains triggerName
    logger.debug(s"Starting trigger $triggerName")(TraceContext.empty)
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

  private def checkPausedTriggersSpelling(
      others: Seq[OrCompanion]
  )(implicit tc: TraceContext): Unit = {
    val services: Seq[OrCompanion] = this +: others
    val allPausedTriggers = services foldMap (_.oas foldMap (_.automationConfig.pausedTriggers))
    val allRegisteredTriggers = services foldMap { oc =>
      oc.companion.expectedTriggers union oc.oas.foldMap(
        _.backgroundServices
          .get()
          .view
          .collect { case t: Trigger => identifyTriggerByName(t) }
          .toSet
      )
    }
    val mismatchedTriggerNames = allPausedTriggers diff allRegisteredTriggers
    if (mismatchedTriggerNames.nonEmpty)
      logger.warn(
        s"paused-triggers specified but not present in {${services.map(_.name) mkString ", "}} (possibly misspelled or specified for wrong app?): ${mismatchedTriggerNames mkString ", "}"
      )
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      SyncCloseable(
        "Orchestrated services",
        LifeCycle.close(backgroundServices.get()*)(logger),
      )
    )
}

object AutomationService {
  import AutomationServiceCompanion.TriggerClass

  private def identifyTriggerByName(trigger: Trigger): String =
    identifyTriggerClassByName(trigger.getClass)

  private[automation] def identifyTriggerClassByName(c: TriggerClass) =
    c.getCanonicalName

  // Allow variadic overloading of checkPausedTriggersSpelling
  final class OrCompanion private (
      private[AutomationService] val oas: Option[AutomationService],
      private[AutomationService] val companion: AutomationServiceCompanion,
  ) {
    private[AutomationService] def name =
      oas.map(_.getClass.getSimpleName) getOrElse companion.getClass.getSimpleName
  }

  object OrCompanion {
    import language.implicitConversions

    implicit def fromService(as: AutomationService): OrCompanion =
      new OrCompanion(Some(as), as.companion)

    implicit def fromCompanion(c: AutomationServiceCompanion): OrCompanion =
      new OrCompanion(None, c)
  }

  /** Ensure every paused trigger is present either in one of the
    * specified [[AutomationService]]s or [[AutomationServiceCompanion]]s.
    * Log a warning if not.
    */
  def checkPausedTriggersSpelling(services: Seq[OrCompanion])(implicit tc: TraceContext): Unit =
    for {
      // there is nothing to check if there are no real AutomationServices,
      // because there will also be no paused triggers then
      first <- services collectFirst Function.unlift(oc => oc.oas.map((oc, _)))
      (matchingService, firstAS) = first
    } firstAS checkPausedTriggersSpelling services.filter(_ ne matchingService)
}
