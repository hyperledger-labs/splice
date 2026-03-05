// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.lfdecentralizedtrust.splice.automation.{ServiceWithShutdown, RetryingService}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider

import scala.concurrent.{ExecutionContext, Future}

class PekkoRetryingService[S](
    source: Source[S, ?],
    // Note that the killSwitch for termination will be placed between the source and the sink.
    sink: Sink[Any, Future[Done]],
    automationConfig: AutomationConfig,
    backoffClock: Clock,
    description: String,
    override protected val retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit as: ActorSystem, ec: ExecutionContext, tracer: Tracer)
    extends RetryingService(automationConfig, backoffClock, description)
    with NamedLogging {

  // kick-off processing
  start()

  private class PekkoServiceWithShutdown extends ServiceWithShutdown {

    private val (killSwitch, done) =
      source.viaMat(KillSwitches.single)(Keep.right).toMat(sink)(Keep.both).run()

    override def initiateShutdown()(implicit tc: TraceContext): Unit = {
      logger.debug(s"Shutting down Pekko stream for $description")
      killSwitch.shutdown()
    }

    override def completed: Future[Done] = done

    override def isActive: Boolean = !done.isCompleted
  }

  override protected def instantiateService()(implicit
      traceContext: TraceContext
  ): Future[ServiceWithShutdown] = Future.successful(new PekkoServiceWithShutdown())

}
