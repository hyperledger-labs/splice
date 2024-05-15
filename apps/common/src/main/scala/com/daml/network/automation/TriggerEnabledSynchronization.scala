package com.daml.network.automation

import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

abstract class TriggerEnabledSynchronization {

  /** Blocks until the trigger is allowed to start its next unit of work. */
  def waitForTriggerEnabled()(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit]
}

object TriggerEnabledSynchronization {
  final class NoopTriggerEnabledSynchronization extends TriggerEnabledSynchronization {
    override def waitForTriggerEnabled()(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Unit] = Future.unit
  }

  /** Triggers that are always enabled */
  val Noop = new NoopTriggerEnabledSynchronization()

  def fromDomainTimeAndParams(
      domainTimeSync: DomainTimeSynchronization,
      domainParamsSync: DomainUnpausedSynchronization,
  ): TriggerEnabledSynchronization = new TriggerEnabledSynchronization {
    override def waitForTriggerEnabled()(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Unit] = {
      for {
        _ <- domainTimeSync.waitForDomainTimeSync()
        _ <- domainParamsSync.waitForDomainUnpaused()
      } yield ()
    }
  }
}
