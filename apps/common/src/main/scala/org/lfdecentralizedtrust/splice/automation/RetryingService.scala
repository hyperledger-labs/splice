// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.apache.pekko.Done
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider}
import org.lfdecentralizedtrust.splice.util.HasHealth
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

abstract class ServiceWithShutdown {
  def initiateShutdown(): Unit
  def completed: Future[Done]
  def isActive: Boolean
}

/** Abstract class to share the retry and shutdown logic between different infinite services,
  * e.g. ones for ingesting ledger data using a subscription to a Ledger API stream.
  */
abstract class RetryingService(
    config: AutomationConfig,
    backoffClock: Clock,
    description: String,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends HasHealth
    with FlagCloseableAsync
    with RetryProvider.Has
    with NamedLogging
    with Spanning {

  protected def retryProvider: RetryProvider

  /** Allocate a new instance of the service. */
  protected def instantiateService()(implicit
      traceContext: TraceContext
  ): Future[ServiceWithShutdown]

  // Note that we are tracking the current instance of the service outside the retry loop instead of just
  // calling 'runOnShutdown' on every newly acquired instantiation, as that would leak memory.
  private val currentServiceInstance =
    new AtomicReference[Option[ServiceWithShutdown]](None)
  private val serviceTerminatedF = new AtomicReference[Future[Done]](Future.successful(Done))

  retryProvider.runOnOrAfterClose_(new RunOnClosing {
    override def name: String = s"terminate service $description"
    // this is not perfectly precise, but `initiateShutdown`s should be idempotent
    override def done: Boolean = false
    override def run()(implicit tc: TraceContext): Unit = currentServiceInstance
      .get()
      .foreach(instance => {
        logger
          .debug(s"Terminating service $description, as we are shutting down.")(TraceContext.empty)
        instance.initiateShutdown()
      })
  })(TraceContext.empty)

  protected def start(): Unit = {
    withNewTrace(description)(implicit traceContext =>
      _ => {
        logger.debug(s"Starting service $description")

        // We use both an infinite loop and retries to ensure we always eventually log an error and
        // always recover from unexpected errors.
        def loopUntilShutdown(): Future[Done] =
          if (retryProvider.isClosing)
            Future.successful(Done)
          else {
            retryProvider
              .retry(
                // We use the LongRunningAutomation retry policy here to ensure that:
                // 1. If we get a lot of errors in a short period of time, we log an error and backoff with the pollingInterval.
                // 2. If we get very infrequent errors (e.g. stale stream authorization on user addition), no error is logged an we get fast retries
                //    instead of sleeping for the polling interval just because a certain number of users got allocated.
                RetryFor.LongRunningAutomation,
                description,
                s"$description service", {
                  instantiateService().flatMap(service => {
                    // Smuggle the current instance out of the body here, so that we can use
                    // runOnShutdown outside to signal the termination via a call to .initiateShutdown().
                    currentServiceInstance.set(Some(service))
                    // The creation of the new service races with the call to close the content of `currentServiceInstance`, which is issued
                    // at most once from outside and might end up closing the previous service set in a retry loop.
                    // We resolve that race by checking here whether we are closing, and issuing the call ourselves.
                    if (retryProvider.isClosing) {
                      logger.debug(
                        "detected race between shutdown and service creation, closing service"
                      )
                      service.initiateShutdown()
                    }
                    // The actual return value of the future being retried is the future inside the SpliceLedgerConnection,
                    // which signals when the instance terminated.
                    service.completed.map(_ =>
                      if (retryProvider.isClosing)
                        Done // This is the normal path that we hit when we are shutting down.
                      else {
                        // Here it looks like the server closed the connection, which is unexpected.
                        // We consider it transient error that we want to retry on in the hope of hitting a live server.
                        throw Status.ABORTED
                          .withDescription(
                            "Unexpected closing of a service instance, likely due to server shutdown."
                          )
                          .asRuntimeException()
                      }
                    )
                  })
                },
                logger,
              )
              .recoverWith { ex =>
                // Note: we want the same failure handling as PollingTriggers, and thus reuse their config
                logger.info(
                  s"Restarting service $description after ${config.pollingInterval} due to unexpected exception:",
                  ex,
                )
                retryProvider
                  .scheduleAfterUnlessShutdown(
                    loopUntilShutdown(),
                    backoffClock,
                    config.pollingInterval,
                    config.pollingJitter,
                  )
                  .onShutdown(Done)
              }
          }

        serviceTerminatedF.set(
          loopUntilShutdown().transform(
            retryProvider.logTerminationAndRecoverOnShutdown(description, logger)
          )
        )
      }
    )
  }

  final override def isHealthy: Boolean =
    // Healthy if there's an active instance
    currentServiceInstance.get().exists(_.isActive)

  final override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    implicit def traceContext: TraceContext = TraceContext.empty
    Seq(
      AsyncCloseable(
        s"waiting for termination of service $description",
        serviceTerminatedF.get(),
        NonNegativeDuration.tryFromDuration(timeouts.shutdownNetwork.duration),
      )
    )
  }
}
